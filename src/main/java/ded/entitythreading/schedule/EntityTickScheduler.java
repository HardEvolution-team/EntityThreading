package ded.entitythreading.schedule;

import ded.entitythreading.EntityThreadingMod;
import ded.entitythreading.config.EntityThreadingConfig;
import ded.entitythreading.interfaces.IEntityActivation;
import ded.entitythreading.interfaces.IMixinWorld;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Core scheduler — distributes entity ticking across Java 25 <b>virtual threads</b>.
 *
 * <h2>Architecture overview</h2>
 * <pre>
 *  Main thread
 *    queueEntity()  ─── accumulates into parallelEntities / mainThreadEntities
 *    waitForFinish() ─┬─ ticks players + blacklisted entities on main thread
 *                     └─ tickParallel():
 *                          ┌─ snapshot loaded chunks
 *                          ├─ one virtual thread per entity  (via newVirtualThreadPerTaskExecutor)
 *                          │    ↳ safeTick()  ─ defers unsafe ops via EntityTickContext → DeferredActionQueue
 *                          └─ StructuredTaskScope.ShutdownOnFailure gathers all results
 *    DeferredActionQueue.replayAll() ─ flushes all deferred ops back on main thread
 * </pre>
 *
 * <h2>Virtual-thread context</h2>
 * Per-thread mutable state (deferred buffer, chunk cache, remote flag) is carried via
 * {@link ScopedValue} {@link #TICK_CONTEXT}.  Every virtual-thread task binds a fresh
 * {@link EntityTickContext} before calling {@link #safeTick}.  At the end of the task the
 * context is flushed to {@link DeferredActionQueue} automatically.
 *
 * <h2>Thread identification</h2>
 * {@link #isEntityThread()} checks for a bound {@code TICK_CONTEXT} or the main-thread
 * worker flag, replacing the old {@code instanceof EntityWorkerThread} pattern.
 */
public final class EntityTickScheduler {

    // --- Configuration ---
    private static final long WORKER_TIMEOUT_MS  = 5_000;
    private static final long COOLDOWN_DURATION_MS = 30_000;

    // --- ScopedValue: carries per-virtual-thread context ---
    /**
     * Bound to an {@link EntityTickContext} for the duration of each entity-tick virtual thread.
     * Reading this value from a non-entity virtual thread returns {@link ScopedValue#isBound} == false.
     */
    public static final ScopedValue<EntityTickContext> TICK_CONTEXT = ScopedValue.newInstance();

    // --- Blacklists ---
    private static final Set<String> blacklistedClasses       = ConcurrentHashMap.newKeySet();
    private static volatile String[] blacklistedModPrefixArray = new String[0];
    private static final Set<String> blacklistedModIds         = ConcurrentHashMap.newKeySet();

    // --- Error tracking (log each class only once) ---
    private static final Set<String> loggedErrorClasses = ConcurrentHashMap.newKeySet();

    // --- Per-tick entity buffers (main thread only, so plain ThreadLocal is fine) ---
    private static final ThreadLocal<ArrayList<Entity>> parallelEntities =
            ThreadLocal.withInitial(() -> new ArrayList<>(4096));
    private static final ThreadLocal<ArrayList<Entity>> mainThreadEntities =
            ThreadLocal.withInitial(() -> new ArrayList<>(256));
    private static final ThreadLocal<World>   currentTickWorld      = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> mainThreadWorkerFlag  = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> mainThreadRemoteFlag  = ThreadLocal.withInitial(() -> false);

    // --- Chunk snapshot (written once before parallel phase, read from many VTs) ---
    private static final Long2ObjectOpenHashMap<Chunk> EMPTY_SNAPSHOT = new Long2ObjectOpenHashMap<>(0);
    private static volatile Long2ObjectOpenHashMap<Chunk> chunkSnapshot = EMPTY_SNAPSHOT;

    // --- Virtual-thread executor (unlimited — JVM decides how many carrier threads to use) ---
    /**
     * One virtual thread is created per entity tick task.  The executor is permanent and
     * never shut down during the game; individual tasks are always short-lived.
     */
    private static final ExecutorService VIRTUAL_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    // Cooldown after stuck detection
    private static volatile long cooldownUntil = 0;

    static {
        rebuildBlacklist();
    }

    private EntityTickScheduler() {}

    // ─────────────────────────────────────────────────────────────────────
    // Blacklist management
    // ─────────────────────────────────────────────────────────────────────

    public static void rebuildBlacklist() {
        blacklistedClasses.clear();
        blacklistedModIds.clear();

        // Default blacklisted entities
        blacklistedClasses.add("net.minecraft.entity.item.EntityItem");
        blacklistedClasses.add("net.minecraft.entity.item.EntityXPOrb");

        // User-configured blacklist
        if (EntityThreadingConfig.blacklistedEntities != null) {
            for (String cls : EntityThreadingConfig.blacklistedEntities) {
                if (cls != null && !cls.isBlank()) {
                    blacklistedClasses.add(cls.strip());
                }
            }
        }

        // Hardcoded mod blacklists (known incompatible mods)
        blacklistedModIds.add("hbm");

        // Rebuild prefix array for fast iteration
        Set<String> prefixes = ConcurrentHashMap.newKeySet();
        prefixes.add("com.hbm.");
        blacklistedModPrefixArray = prefixes.toArray(new String[0]);
    }

    public static boolean isModEventBlacklisted(String modId) {
        return blacklistedModIds.contains(modId);
    }

    private static boolean isBlacklisted(Entity entity) {
        String className = entity.getClass().getName();
        if (blacklistedClasses.contains(className)) return true;
        String[] prefixes = blacklistedModPrefixArray;
        for (int i = 0; i < prefixes.length; i++) {
            if (className.startsWith(prefixes[i])) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Entity queuing  (called from ASM-patched updateEntities)
    // ─────────────────────────────────────────────────────────────────────

    public static void queueEntity(World world, Entity entity) {
        if (!EntityThreadingConfig.enabled || world.isRemote) {
            ((IMixinWorld) world).entitythreading$tickEntityDirectly(entity);
            return;
        }

        currentTickWorld.set(world);

        if (entity instanceof EntityPlayer || isBlacklisted(entity)) {
            mainThreadEntities.get().add(entity);
        } else {
            parallelEntities.get().add(entity);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tick execution
    // ─────────────────────────────────────────────────────────────────────

    public static void waitForFinish() {
        if (!EntityThreadingConfig.enabled) return;

        ArrayList<Entity> parallel   = parallelEntities.get();
        ArrayList<Entity> mainThread = mainThreadEntities.get();
        World world = currentTickWorld.get();

        if (world == null) return;

        boolean isRemote = world.isRemote;
        mainThreadRemoteFlag.set(isRemote);
        mainThreadWorkerFlag.set(false);

        try {
            // Distance-based tick throttling
            if (EntityThreadingConfig.entityActivationRange && !isRemote) {
                EntityActivationRange.activateEntities(world);
            }

            // Players and blacklisted entities stay on main thread
            tickEntitiesOnMainThread(world, mainThread);

            int parallelCount = parallel.size();
            if (parallelCount > 0) {
                boolean inCooldown = System.currentTimeMillis() < cooldownUntil;
                if (parallelCount < EntityThreadingConfig.minEntitiesForThreading || inCooldown) {
                    tickEntitiesOnMainThread(world, parallel);
                } else {
                    tickParallelVirtual(world, parallel, isRemote);
                }
            }

            DeferredActionQueue.replayAll(isRemote);
        } finally {
            currentTickWorld.remove();
        }
    }

    private static void tickEntitiesOnMainThread(World world, ArrayList<Entity> entities) {
        if (entities.isEmpty()) return;
        for (int i = 0, size = entities.size(); i < size; i++) {
            Entity entity = entities.get(i);
            try {
                world.updateEntity(entity);
            } catch (Exception e) {
                logError(entity, e);
            }
        }
        entities.clear();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Parallel ticking via Virtual Threads
    // ─────────────────────────────────────────────────────────────────────

    private static void buildChunkSnapshot(World world) {
        try {
            ChunkProviderServer provider = (ChunkProviderServer) world.getChunkProvider();
            Collection<Chunk> loaded = provider.getLoadedChunks();
            Long2ObjectOpenHashMap<Chunk> snap = new Long2ObjectOpenHashMap<>(loaded.size() + 16);
            for (Chunk chunk : loaded) {
                snap.put(ChunkPos.asLong(chunk.x, chunk.z), chunk);
            }
            chunkSnapshot = snap;
        } catch (Exception e) {
            EntityThreadingMod.LOGGER.error("Failed to build chunk snapshot: {}", e.getMessage());
            chunkSnapshot = EMPTY_SNAPSHOT;
        }
    }

    /**
     * Returns the chunk from the per-tick snapshot.
     * Uses the per-virtual-thread chunk cache (stored in {@link EntityTickContext}) when
     * called from a virtual entity-tick thread, otherwise falls back to a direct map lookup.
     */
    public static Chunk getChunkFromSnapshot(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        if (TICK_CONTEXT.isBound()) {
            EntityTickContext ctx = TICK_CONTEXT.get();
            if (ctx.lastChunkKey == key && ctx.lastChunk != null) {
                return (Chunk) ctx.lastChunk;
            }
            Chunk c = chunkSnapshot.get(key);
            ctx.lastChunkKey = key;
            ctx.lastChunk    = c;
            return c;
        }
        return chunkSnapshot.get(key);
    }

    /**
     * Spawns virtual threads using {@link StructuredTaskScope}.
     * Groups 10 entities per virtual thread to reduce overhead.
     * All virtual threads must complete before the scope exits; if any fail with an
     * unhandled exception the scope shuts down remaining work and rethrows.
     */
    @SuppressWarnings("preview")
    private static void tickParallelVirtual(World world, ArrayList<Entity> entries, boolean isRemote) {
        int totalEntities = entries.size();

        // Snapshot entities into an array so we can clear the list immediately
        Entity[] tickArray = entries.toArray(new Entity[0]);
        entries.clear();

        buildChunkSnapshot(world);

        // StructuredTaskScope (Java 25 JEP 505 API):
        // Default policy is to use virtual threads. We use allUntilFailed() joiner
        // which mimics the old ShutdownOnFailure behavior.
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow(),
                cfg -> cfg.withThreadFactory(Thread.ofVirtual().name("EntityTick-VT-", 0).factory())
                          .withTimeout(Duration.ofMillis(WORKER_TIMEOUT_MS)))) {

            int batchSize = Math.max(1, EntityThreadingConfig.entitiesPerVirtualThread);
            for (int i = 0; i < totalEntities; i += batchSize) {
                final int startIdx = i;
                final int endIdx = Math.min(i + batchSize, totalEntities);

                scope.fork(() -> {
                    // Each virtual thread gets its own fresh context bound via ScopedValue
                    EntityTickContext ctx = new EntityTickContext(isRemote);
                    ScopedValue.where(TICK_CONTEXT, ctx).run(() -> {
                        for (int j = startIdx; j < endIdx; j++) {
                            Entity entity = tickArray[j];
                            if (entity != null) {
                                safeTick(world, entity);
                            }
                        }
                    });
                    // Flush deferred actions accumulated during this tick
                    ctx.flush();
                    return null;
                });
            }

            // Wait for ALL virtual threads, with a timeout guard
            try {
                scope.join();
            } catch (StructuredTaskScope.TimeoutException e) {
                handleTimeout(totalEntities);
            } catch (StructuredTaskScope.FailedException e) {
                // One or more subtasks failed
                EntityThreadingMod.LOGGER.error("Entity tick subtask error: {}", e.getMessage(), e);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            EntityThreadingMod.LOGGER.warn("Entity ticking interrupted");
        } finally {
            chunkSnapshot = EMPTY_SNAPSHOT;
        }
    }

    private static void handleTimeout(int entityCount) {
        EntityThreadingMod.LOGGER.warn(
                "Virtual entity tick timed out after {}ms for {} entities. Entering cooldown.",
                WORKER_TIMEOUT_MS, entityCount);
        cooldownUntil = System.currentTimeMillis() + COOLDOWN_DURATION_MS;
        EntityThreadingMod.LOGGER.error("Parallel ticking disabled for {}s cooldown.",
                COOLDOWN_DURATION_MS / 1000);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Safe entity ticking
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Ticks a single entity.  Delegates to {@code World.updateEntity} for full ticks,
     * or runs {@link #minimalTick} for distance-throttled entities.
     * <p>
     * Called from within a virtual thread — {@link #TICK_CONTEXT} is bound.
     */
    private static void safeTick(World world, Entity entity) {
        if (entity.isDead) return;

        boolean useActivation = EntityThreadingConfig.entityActivationRange && !world.isRemote;

        if (useActivation) {
            int interval = ((IEntityActivation) entity).entitythreading$getTickInterval();
            if (interval > 1 && (entity.ticksExisted % interval) != 0) {
                minimalTick(entity);
                return;
            }
        }

        // Record chunk position BEFORE tick
        int oldCX = entity.chunkCoordX;
        int oldCY = entity.chunkCoordY;
        int oldCZ = entity.chunkCoordZ;

        try {
            world.updateEntity(entity);
        } catch (Throwable t) {
            if (loggedErrorClasses.add(entity.getClass().getName())) {
                EntityThreadingMod.LOGGER.error("{} tick error: {}",
                        entity.getClass().getSimpleName(), t.getMessage(), t);
            }
            return;
        }

        // Defer chunk-position update if entity crossed chunk boundaries
        int newCX = MathHelper.floor(entity.posX / 16.0);
        int newCY = MathHelper.floor(entity.posY / 16.0);
        int newCZ = MathHelper.floor(entity.posZ / 16.0);

        if (newCX != oldCX || newCY != oldCY || newCZ != oldCZ) {
            DeferredActionQueue.enqueue(() ->
                    ((IMixinWorld) world).entitythreading$updateChunkPos(entity));
        }

        tickPassengers(world, entity);
    }

    /**
     * Minimal tick for throttled entities — increments age and syncs prev-position to
     * prevent interpolation glitches on the next full tick.
     */
    private static void minimalTick(Entity entity) {
        ++entity.ticksExisted;
        entity.prevPosX      = entity.posX;
        entity.prevPosY      = entity.posY;
        entity.prevPosZ      = entity.posZ;
        entity.prevRotationYaw   = entity.rotationYaw;
        entity.prevRotationPitch = entity.rotationPitch;
        // DO NOT modify posY / motionY — causes chunk-tracking desync
    }

    private static void tickPassengers(World world, Entity entity) {
        try {
            List<Entity> passengers = entity.getPassengers();
            if (passengers.isEmpty()) return;

            Entity[] passArray = passengers.toArray(new Entity[0]);
            for (Entity passenger : passArray) {
                if (passenger.isDead || passenger.getRidingEntity() != entity) {
                    DeferredActionQueue.enqueue(() -> {
                        try { passenger.dismountRidingEntity(); }
                        catch (Exception e) {
                            EntityThreadingMod.LOGGER.debug("Dismount failed: {}", e.getMessage());
                        }
                    });
                    continue;
                }
                if (passenger instanceof EntityPlayer || isBlacklisted(passenger)) {
                    DeferredActionQueue.enqueue(() -> {
                        try { world.updateEntity(passenger); }
                        catch (Exception e) {
                            EntityThreadingMod.LOGGER.debug("Passenger tick failed: {}", e.getMessage());
                        }
                    });
                } else {
                    safeTick(world, passenger);
                }
            }
        } catch (Throwable t) {
            if (loggedErrorClasses.add(entity.getClass().getName() + "_passengers")) {
                EntityThreadingMod.LOGGER.error("Passenger tick error for {}: {}",
                        entity.getClass().getSimpleName(), t.getMessage(), t);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Thread identification
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when the current thread is executing an entity tick —
     * either a virtual entity-tick thread (TICK_CONTEXT is bound) or the main thread
     * participating as a work-stealer.
     */
    public static boolean isEntityThread() {
        return TICK_CONTEXT.isBound() || Boolean.TRUE.equals(mainThreadWorkerFlag.get());
    }

    /**
     * Returns {@code true} when the current entity-tick thread is processing a
     * remote (client-side) world.
     */
    public static boolean isCurrentThreadRemote() {
        if (TICK_CONTEXT.isBound()) {
            return TICK_CONTEXT.get().isRemote;
        }
        return Boolean.TRUE.equals(mainThreadRemoteFlag.get());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns a shared {@link ExecutorService} backed by virtual threads.
     * Used by {@link AsyncPathProcessor} for async pathfinding submissions.
     */
    public static ExecutorService getSharedPool() {
        return VIRTUAL_EXECUTOR;
    }

    public static void reinitialize() {
        rebuildBlacklist();
        EntityThreadingMod.LOGGER.info("EntityThreading: virtual-thread executor active (no pool needed).");
    }

    public static void shutdown() {
        // Virtual executor is intentionally kept alive until JVM exit (tasks are ephemeral).
        // Just clean up per-tick state.
        parallelEntities.get().clear();
        mainThreadEntities.get().clear();
        currentTickWorld.remove();
        DeferredActionQueue.clear();
        AsyncPathProcessor.shutdown();
        chunkSnapshot = EMPTY_SNAPSHOT;
        loggedErrorClasses.clear();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Error logging
    // ─────────────────────────────────────────────────────────────────────

    private static void logError(Entity entity, Exception e) {
        if (loggedErrorClasses.add(entity.getClass().getName())) {
            EntityThreadingMod.LOGGER.error("Tick error for {}: {}",
                    entity.getClass().getSimpleName(), e.getMessage(), e);
        }
    }
}
