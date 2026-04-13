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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Core scheduler that distributes entity ticking across worker threads.
 * <p>
 * Entities are queued during {@code World.updateEntities()} via ASM-injected calls
 * to {@link #queueEntity}, then processed in parallel during {@link #waitForFinish}.
 * <p>
 * Thread-unsafe operations (block changes, entity spawning, etc.) are deferred
 * to the main thread via {@link DeferredActionQueue}.
 * <p>
 * Supports distance-based tick throttling: entities farther from players tick less
 * frequently (every 2nd, 4th, or 8th tick) instead of being fully disabled.
 */
public final class EntityTickScheduler {

    // --- Configuration ---
    private static final long WORKER_TIMEOUT_MS = 3_000;
    private static final long COOLDOWN_DURATION_MS = 30_000;
    private static final int BATCH_SIZE = 64;

    // --- Blacklists ---
    private static final Set<String> blacklistedClasses = ConcurrentHashMap.newKeySet();
    // Use a volatile array for prefix matching — faster iteration than ConcurrentHashMap.KeySetView
    private static volatile String[] blacklistedModPrefixArray = new String[0];
    private static final Set<String> blacklistedModIds = ConcurrentHashMap.newKeySet();

    // --- Error tracking (log each class only once) ---
    private static final Set<String> loggedErrorClasses = ConcurrentHashMap.newKeySet();

    // --- Per-tick entity buffers ---
    private static final ThreadLocal<ArrayList<Entity>> parallelEntities =
            ThreadLocal.withInitial(() -> new ArrayList<>(4096));
    private static final ThreadLocal<ArrayList<Entity>> mainThreadEntities =
            ThreadLocal.withInitial(() -> new ArrayList<>(256));
    private static final ThreadLocal<World> currentTickWorld = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> mainThreadWorkerFlag = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> mainThreadRemoteFlag = ThreadLocal.withInitial(() -> false);

    // --- Chunk snapshot ---
    private static final Long2ObjectOpenHashMap<Chunk> EMPTY_SNAPSHOT = new Long2ObjectOpenHashMap<>(0);
    private static volatile Long2ObjectOpenHashMap<Chunk> chunkSnapshot = EMPTY_SNAPSHOT;

    // --- Thread pool ---
    private static final CopyOnWriteArrayList<Thread> activeWorkerThreads = new CopyOnWriteArrayList<>();
    private static volatile ExecutorService threadPool;
    private static int currentThreadCount = 0;
    private static volatile long cooldownUntil = 0;

    // --- Reusable array buffer to avoid toArray allocation every tick ---
    private static Entity[] tickArrayBuffer = new Entity[4096];

    static {
        initThreadPool();
        rebuildBlacklist();
    }

    private EntityTickScheduler() {}

    // --- Initialization ---

    private static void initThreadPool() {
        int threads = EntityThreadingConfig.getEffectiveThreadCount();
        if (threads == currentThreadCount && threadPool != null && !threadPool.isShutdown()) {
            return;
        }
        if (threadPool != null) {
            threadPool.shutdownNow();
        }

        AtomicInteger counter = new AtomicInteger(0);
        threadPool = new ThreadPoolExecutor(
                threads, threads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> new EntityWorkerThread(r, "EntityThreading-Worker-" + counter.getAndIncrement())
        );

        currentThreadCount = threads;
        EntityThreadingMod.LOGGER.info("Thread pool initialized: {} workers", threads);
    }

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
        if (blacklistedClasses.contains(className)) {
            return true;
        }
        // Fast array iteration instead of ConcurrentHashMap.KeySetView
        String[] prefixes = blacklistedModPrefixArray;
        for (int i = 0; i < prefixes.length; i++) {
            if (className.startsWith(prefixes[i])) {
                return true;
            }
        }
        return false;
    }

    // --- Entity queuing (called from ASM-patched updateEntities) ---

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

    // --- Tick execution ---

    public static void waitForFinish() {
        if (!EntityThreadingConfig.enabled) {
            return;
        }

        ArrayList<Entity> parallel = parallelEntities.get();
        ArrayList<Entity> mainThread = mainThreadEntities.get();
        World world = currentTickWorld.get();

        if (world == null) {
            return;
        }

        boolean isRemote = world.isRemote;
        mainThreadRemoteFlag.set(isRemote);
        mainThreadWorkerFlag.set(false);

        try {
            // Compute tick intervals for all entities based on distance to players
            if (EntityThreadingConfig.entityActivationRange && !isRemote) {
                EntityActivationRange.activateEntities(world);
            }

            tickEntitiesOnMainThread(world, mainThread);

            int parallelCount = parallel.size();
            if (parallelCount > 0) {
                boolean inCooldown = System.currentTimeMillis() < cooldownUntil;
                if (parallelCount < EntityThreadingConfig.minEntitiesForThreading || inCooldown) {
                    tickEntitiesOnMainThread(world, parallel);
                } else {
                    tickParallel(world, parallel, isRemote);
                }
            }

            DeferredActionQueue.replayAll(isRemote);
        } finally {
            currentTickWorld.remove();
        }
    }

    private static void tickEntitiesOnMainThread(World world, ArrayList<Entity> entities) {
        if (entities.isEmpty()) {
            return;
        }
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

    // --- Parallel ticking ---

    private static void buildChunkSnapshot(World world) {
        try {
            // Safe cast — we only call this for server worlds (isRemote check in queueEntity)
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

    public static Chunk getChunkFromSnapshot(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        Thread t = Thread.currentThread();
        if (t instanceof EntityWorkerThread ewt) {
            if (ewt.lastChunkKey == key && ewt.lastChunk != null) {
                return (Chunk) ewt.lastChunk;
            }
            Chunk c = chunkSnapshot.get(key);
            ewt.lastChunkKey = key;
            ewt.lastChunk = c;
            return c;
        }
        return chunkSnapshot.get(key);
    }

    private static void tickParallel(World world, ArrayList<Entity> entries, boolean isRemote) {
        int totalEntities = entries.size();

        // Reuse array buffer to avoid allocation
        if (tickArrayBuffer.length < totalEntities) {
            tickArrayBuffer = new Entity[Math.max(totalEntities, tickArrayBuffer.length * 2)];
        }
        Entity[] tickArray = tickArrayBuffer;
        for (int i = 0; i < totalEntities; i++) {
            tickArray[i] = entries.get(i);
        }
        entries.clear();

        buildChunkSnapshot(world);

        int workerCount = currentThreadCount;
        AtomicInteger currentIndex = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(workerCount);
        activeWorkerThreads.clear();

        // Submit worker tasks
        for (int t = 0; t < workerCount; t++) {
            threadPool.execute(() -> {
                Thread ct = Thread.currentThread();
                activeWorkerThreads.add(ct);
                EntityWorkerThread ewt = (EntityWorkerThread) ct;
                ewt.resetForTask(isRemote);
                try {
                    processEntityBatches(world, tickArray, totalEntities, currentIndex);
                } finally {
                    ArrayList<Runnable> buf = ewt.deferredBuffer;
                    if (!buf.isEmpty()) {
                        DeferredActionQueue.submitWorkerBuffer(new ArrayList<>(buf), isRemote);
                        buf.clear();
                    }
                    ewt.finishTask();
                    activeWorkerThreads.remove(ct);
                    latch.countDown();
                }
            });
        }

        // Main thread also participates in work-stealing
        mainThreadWorkerFlag.set(true);
        mainThreadRemoteFlag.set(isRemote);
        try {
            processEntityBatches(world, tickArray, totalEntities, currentIndex);
        } finally {
            mainThreadWorkerFlag.set(false);
        }

        // Wait for workers
        try {
            if (!latch.await(WORKER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                handleStuckWorkers(latch);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Clear references in buffer to avoid holding entity references
        for (int i = 0; i < totalEntities; i++) {
            tickArray[i] = null;
        }

        chunkSnapshot = EMPTY_SNAPSHOT;
    }

    private static void processEntityBatches(World world, Entity[] tickArray,
                                             int totalEntities, AtomicInteger currentIndex) {
        int idx;
        while ((idx = currentIndex.getAndAdd(BATCH_SIZE)) < totalEntities) {
            int end = Math.min(idx + BATCH_SIZE, totalEntities);
            for (int j = idx; j < end; j++) {
                safeTick(world, tickArray[j]);
            }
        }
    }

    private static void handleStuckWorkers(CountDownLatch latch) {
        int stuckCount = (int) latch.getCount();
        EntityThreadingMod.LOGGER.warn("{} worker(s) stuck after {}ms!", stuckCount, WORKER_TIMEOUT_MS);

        for (Thread worker : activeWorkerThreads) {
            EntityThreadingMod.LOGGER.error("Stuck thread: {}", worker.getName());
            for (StackTraceElement ste : worker.getStackTrace()) {
                EntityThreadingMod.LOGGER.error("  at {}", ste);
            }
        }
        activeWorkerThreads.clear();

        cooldownUntil = System.currentTimeMillis() + COOLDOWN_DURATION_MS;
        EntityThreadingMod.LOGGER.error("Parallel ticking disabled for {}s cooldown.",
                COOLDOWN_DURATION_MS / 1000);
    }

    // --- Safe entity ticking ---

    /**
     * Ticks a single entity, delegating to World.updateEntity which handles
     * all vanilla logic (prevPos, ticksExisted, chunk tracking, etc.)
     * <p>
     * If activation range is enabled, checks the entity's tick interval for throttling.
     * On skipped ticks, only minimal processing runs.
     * <p>
     * CRITICAL FIX: Previous version duplicated vanilla updateEntity logic
     * (prevPos, lastTickPos, ticksExisted updates). This caused double-updates
     * and broke render interpolation. Now we delegate to World.updateEntity
     * for full ticks and only do truly minimal work for throttled ticks.
     */
    private static void safeTick(World world, Entity entity) {
        if (entity.isDead) {
            return;
        }

        boolean useActivation = EntityThreadingConfig.entityActivationRange && !world.isRemote;

        if (useActivation) {
            int interval = ((IEntityActivation) entity).entitythreading$getTickInterval();
            if (interval > 1 && (entity.ticksExisted % interval) != 0) {
                // Throttled tick — only minimal processing
                minimalTick(entity);
                return;
            }
        }

        // Record chunk position BEFORE tick
        int oldCX = entity.chunkCoordX;
        int oldCY = entity.chunkCoordY;
        int oldCZ = entity.chunkCoordZ;

        try {
            // Delegate to vanilla World.updateEntity which handles:
            // - prevPos/lastTickPos updates
            // - ticksExisted increment
            // - onUpdate() / updateRidden()
            // - forceChunkLoading
            // - profiler sections
            world.updateEntity(entity);
        } catch (Throwable t) {
            if (loggedErrorClasses.add(entity.getClass().getName())) {
                EntityThreadingMod.LOGGER.error("{} tick error: {}",
                        entity.getClass().getSimpleName(), t.getMessage(), t);
            }
            return;
        }

        // Check if entity moved to a different chunk
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
     * Minimal tick for throttled entities — keeps them alive and aging
     * without running full AI/movement logic.
     * <p>
     * CRITICAL FIX: Previous version modified entity.posY directly, which
     * desynchronizes position from chunk tracking. Throttled entities should
     * NOT have their position modified — they just age.
     * <p>
     * Handles:
     * <ul>
     *   <li>Age increment (so despawn timers still work)</li>
     *   <li>Previous position sync (prevents visual glitches on next full tick)</li>
     * </ul>
     */
    private static void minimalTick(Entity entity) {
        ++entity.ticksExisted;

        // Sync previous position to current — prevents interpolation glitches
        // when the entity resumes full ticking
        entity.prevPosX = entity.posX;
        entity.prevPosY = entity.posY;
        entity.prevPosZ = entity.posZ;
        entity.prevRotationYaw = entity.rotationYaw;
        entity.prevRotationPitch = entity.rotationPitch;

        // DO NOT modify posY or motionY here — that causes chunk tracking desync
        // DO NOT call extinguish() — that fires events and modifies state
        // The entity will catch up on its next full tick
    }

    private static void tickPassengers(World world, Entity entity) {
        try {
            List<Entity> passengers = entity.getPassengers();
            if (passengers.isEmpty()) {
                return;
            }
            // Snapshot to array to avoid CME
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

    // --- Thread identification ---

    public static boolean isEntityThread() {
        if (EntityWorkerThread.isCurrentThreadWorker()) {
            return true;
        }
        return Boolean.TRUE.equals(mainThreadWorkerFlag.get());
    }

    public static boolean isCurrentThreadRemote() {
        Thread t = Thread.currentThread();
        if (t instanceof EntityWorkerThread ewt) {
            return ewt.isRemote;
        }
        return Boolean.TRUE.equals(mainThreadRemoteFlag.get());
    }

    // --- Public API ---

    public static ExecutorService getSharedPool() {
        return threadPool;
    }

    public static void reinitialize() {
        initThreadPool();
        rebuildBlacklist();
    }

    public static void shutdown() {
        if (threadPool != null) {
            threadPool.shutdownNow();
        }
        parallelEntities.get().clear();
        mainThreadEntities.get().clear();
        currentTickWorld.remove();
        DeferredActionQueue.clear();
        AsyncPathProcessor.shutdown();
        chunkSnapshot = EMPTY_SNAPSHOT;
        loggedErrorClasses.clear();
    }

    // --- Error logging ---

    private static void logError(Entity entity, Exception e) {
        if (loggedErrorClasses.add(entity.getClass().getName())) {
            EntityThreadingMod.LOGGER.error("Tick error for {}: {}",
                    entity.getClass().getSimpleName(), e.getMessage(), e);
        }
    }
}
