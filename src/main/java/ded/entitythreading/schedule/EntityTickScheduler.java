package ded.entitythreading.schedule;

import ded.entitythreading.EntityThreadingConfig;
import ded.entitythreading.transform.IMixinWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.*;

/**
 * High-performance entity tick scheduler.
 *
 * Two distribution modes:
 * 1. BALANCED: Collects all entities into a flat list, splits evenly across N
 * threads.
 * Best for 10k+ entities — guarantees all cores are fully utilized.
 * 2. REGION: Groups entities by chunk region for spatial locality.
 * Better for moderate entity counts with clustered entities.
 *
 * Both modes defer unsafe world mutations to DeferredActionQueue.
 */
public class EntityTickScheduler {

    // Thread pool
    private static ForkJoinPool threadPool;
    private static int currentThreadCount = 0;

    // Blacklisted entity class names (O(1) lookup) - includes config + runtime
    // auto-blacklisted
    private static final Set<String> blacklistedClasses = ConcurrentHashMap.newKeySet();
    // Runtime auto-blacklisted (entities that crashed on worker threads)
    private static final Set<String> runtimeBlacklisted = ConcurrentHashMap.newKeySet();
    // Log dedup: only log once per entity class to prevent spam
    private static final Set<String> loggedErrorClasses = ConcurrentHashMap.newKeySet();

    // === BALANCED MODE: flat entity batching ===
    private static final List<EntityTickEntry> allEntities = Collections.synchronizedList(new ArrayList<>(4096));
    private static final List<EntityTickEntry> mainThreadEntities = Collections.synchronizedList(new ArrayList<>(128));

    // === REGION MODE: chunk-based grouping ===
    private static final ConcurrentHashMap<ChunkGroupKey, EntityGroup> groups = new ConcurrentHashMap<>(256);

    // Stats
    private static volatile int lastEntityCount = 0;
    private static volatile int lastThreadsUsed = 0;
    private static volatile int lastDeferredCount = 0;

    private static final String THREAD_NAME_PREFIX = "EntityThreading-Worker-";

    static {
        initThreadPool();
        rebuildBlacklist();
    }

    private static void initThreadPool() {
        int threads = EntityThreadingConfig.getEffectiveThreadCount();
        if (threads == currentThreadCount && threadPool != null && !threadPool.isShutdown()) {
            return;
        }
        if (threadPool != null) {
            threadPool.shutdownNow();
        }

        // Custom thread factory to tag our threads for reliable isEntityThread() check
        ForkJoinPool.ForkJoinWorkerThreadFactory factory = pool -> {
            ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            worker.setName(THREAD_NAME_PREFIX + worker.getPoolIndex());
            return worker;
        };

        threadPool = new ForkJoinPool(threads, factory, (t, e) -> {
            System.err.println("[EntityThreading] FATAL error in worker thread " + t.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }, true);

        currentThreadCount = threads;
        System.out.println("[EntityThreading] Thread pool initialized: " + threads + " workers");
    }

    public static void rebuildBlacklist() {
        blacklistedClasses.clear();
        if (EntityThreadingConfig.blacklistedEntities != null) {
            for (String cls : EntityThreadingConfig.blacklistedEntities) {
                if (cls != null && !cls.trim().isEmpty()) {
                    blacklistedClasses.add(cls.trim());
                }
            }
        }
    }

    /**
     * Called by ASM hook instead of World.updateEntity(entity).
     * Collects entities for batch processing.
     */
    public static void queueEntity(World world, Entity entity) {
        if (!EntityThreadingConfig.enabled || !isEntityThread()) {
            // Not a worker thread (likely main thread) - tick directly but avoid recursion
            // We use the patched method but our Mixin logic will ensure it's not queued
            // again
            // if we are on the main thread.
            // Wait, if we call world.updateEntity(entity) here, it's redirected again.
            // We need a way to call the ORIGINAL updateEntity.
            ((IMixinWorld) world).entitythreading$tickEntityDirectly(entity);
            return;
        }

        // Players ALWAYS tick on main thread (network packets, movement, inventory)
        if (entity instanceof EntityPlayer) {
            mainThreadEntities.add(new EntityTickEntry(world, entity));
            return;
        }

        String className = entity.getClass().getName();

        // Blacklisted entities (config + runtime auto-blacklisted) tick on main thread
        if (blacklistedClasses.contains(className) || runtimeBlacklisted.contains(className)) {
            mainThreadEntities.add(new EntityTickEntry(world, entity));
            return;
        }

        if (isBalancedMode()) {
            allEntities.add(new EntityTickEntry(world, entity));
        } else {
            // Region mode
            int regionSize = EntityThreadingConfig.regionSize;
            int regionX = Math.floorDiv(entity.chunkCoordX, regionSize);
            int regionZ = Math.floorDiv(entity.chunkCoordZ, regionSize);
            int dimension = world.provider.getDimension();

            ChunkGroupKey key = new ChunkGroupKey(dimension, regionX, regionZ);
            EntityGroup group = groups.computeIfAbsent(key, k -> new EntityGroup(world));
            group.addEntity(entity);
        }
    }

    /**
     * Called by ASM hook at the end of World.updateEntities().
     * Ticks main thread entities, dispatches worker threads, waits, replays
     * deferred actions.
     */
    public static void waitForFinish() {
        if (!EntityThreadingConfig.enabled) {
            return;
        }

        // 1. Tick main thread entities first (players, blacklisted)
        if (!mainThreadEntities.isEmpty()) {
            EntityTickEntry[] mainArray;
            synchronized (mainThreadEntities) {
                mainArray = mainThreadEntities.toArray(new EntityTickEntry[0]);
                mainThreadEntities.clear();
            }

            for (EntityTickEntry entry : mainArray) {
                try {
                    entry.world.updateEntity(entry.entity);
                } catch (Exception e) {
                    System.err.println("[EntityThreading] Error ticking main thread entity " +
                            entry.entity.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }

        // 2. Dispatch worker threads
        if (isBalancedMode()) {
            tickBalanced();
        } else {
            tickRegionBased();
        }

        // 3. Replay deferred world mutations on the main thread
        int deferred = DeferredActionQueue.replayAll();
        lastDeferredCount = deferred;

        if (EntityThreadingConfig.debugLogging) {
            System.out.println("[EntityThreading] Ticked " + lastEntityCount + " entities across " +
                    lastThreadsUsed + " threads, " + lastDeferredCount + " deferred actions replayed");
        }
    }

    // ==================== BALANCED MODE ====================

    private static void tickBalanced() {
        EntityTickEntry[] tickArray;
        synchronized (allEntities) {
            if (allEntities.isEmpty())
                return;
            tickArray = allEntities.toArray(new EntityTickEntry[0]);
            allEntities.clear();
        }

        int totalEntities = tickArray.length;
        int threads = currentThreadCount;
        int actualThreads = Math.min(threads, totalEntities);
        if (actualThreads <= 0)
            return;

        int batchSize = (totalEntities + actualThreads - 1) / actualThreads;
        CountDownLatch latch = new CountDownLatch(actualThreads);

        for (int t = 0; t < actualThreads; t++) {
            int start = t * batchSize;
            int end = Math.min(start + batchSize, totalEntities);

            threadPool.execute(() -> {
                try {
                    for (int i = start; i < end; i++) {
                        EntityTickEntry entry = tickArray[i];
                        safeTick(entry.world, entry.entity);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Deadlock-resistant wait: process deferred actions while waiting for workers
        while (latch.getCount() > 0) {
            if (!DeferredActionQueue.replayOne()) {
                // If no deferred actions, do a tiny sleep to prevent 100% CPU usage on main
                // thread
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        lastEntityCount = totalEntities;
        lastThreadsUsed = actualThreads;
    }

    /**
     * Safely tick a single entity on a worker thread.
     * Mirror of World.updateEntityWithOptionalForce but defers side-effects.
     */
    public static void safeTick(World world, Entity entity) {
        // Snapshot for chunk check
        int cx = entity.chunkCoordX;
        int cy = entity.chunkCoordY;
        int cz = entity.chunkCoordZ;

        try {
            // Update last pos (Crucial for client-side interpolation - fixes "jittering")
            entity.lastTickPosX = entity.posX;
            entity.lastTickPosY = entity.posY;
            entity.lastTickPosZ = entity.posZ;
            entity.prevRotationYaw = entity.rotationYaw;
            entity.prevRotationPitch = entity.rotationPitch;

            if (entity.addedToChunk) {
                entity.ticksExisted++;

                // Mounts update their passengers, so we only tick if NOT riding something
                if (entity.isRiding()) {
                    entity.updateRidden();
                } else {
                    entity.onUpdate();
                }
            }

            // Defer unsafe side-effects to main thread

            // 1. Chunk boundary check (Calculated manually since chunkCoordX isn't updated
            // till updateChunkPos)
            int newCX = MathHelper.floor(entity.posX / 16.0D);
            int newCY = MathHelper.floor(entity.posY / 16.0D);
            int newCZ = MathHelper.floor(entity.posZ / 16.0D);

            if (newCX != cx || newCY != cy || newCZ != cz) {
                DeferredActionQueue.enqueue(() -> ((IMixinWorld) world).entitythreading$updateChunkPos(entity));
            }

            // Entity tracker (Syncs pos/data to clients) happens automatically in
            // EntityTracker.tick()
            // after World.updateEntities(). No manual call needed here.
        } catch (Throwable t) {
            autoBlacklist(entity.getClass().getName(), entity.getClass().getSimpleName(), t.getMessage(), t);
        }
    }

    /**
     * Called by EntityGroup to auto-blacklist a failing entity class.
     * Public so it can be called from EntityGroup.runTick().
     */
    public static void autoBlacklist(String className, String simpleName, String errorMsg, Throwable t) {
        runtimeBlacklisted.add(className);
        if (loggedErrorClasses.add(className)) {
            System.err.println("[EntityThreading] Auto-blacklisted " + simpleName +
                    " (thread-unsafe, will tick on main thread): " + errorMsg);
            if (t != null) {
                t.printStackTrace();
            }
        }
    }

    // ==================== REGION MODE ====================

    private static void tickRegionBased() {
        List<EntityGroup> activeTasks = new ArrayList<>();
        List<ChunkGroupKey> toEvict = new ArrayList<>();

        for (Map.Entry<ChunkGroupKey, EntityGroup> entry : groups.entrySet()) {
            EntityGroup group = entry.getValue();
            if (group.hasEntities()) {
                activeTasks.add(group);
                group.resetIdleTicks();
            } else {
                if (group.incrementIdleAndCheck(EntityThreadingConfig.groupEvictionTicks)) {
                    toEvict.add(entry.getKey());
                }
            }
        }

        for (ChunkGroupKey key : toEvict) {
            groups.remove(key);
        }

        if (activeTasks.isEmpty()) {
            lastEntityCount = 0;
            lastThreadsUsed = 0;
            return;
        }

        int taskCount = activeTasks.size();
        CountDownLatch latch = new CountDownLatch(taskCount);

        int totalEntities = 0;
        for (EntityGroup group : activeTasks) {
            totalEntities += group.getEntityCount();
            threadPool.execute(() -> {
                try {
                    group.runTick();
                } catch (Throwable t) {
                    System.err.println("[EntityThreading] Error in group tick: " + t.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        // Deadlock-resistant wait: process deferred actions while waiting for workers
        while (latch.getCount() > 0) {
            if (!DeferredActionQueue.replayOne()) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        lastEntityCount = totalEntities;
        lastThreadsUsed = taskCount;
    }

    // ==================== UTILITY ====================

    private static boolean isBalancedMode() {
        return "balanced".equalsIgnoreCase(EntityThreadingConfig.distributionMode);
    }

    /**
     * Returns true if the current thread is a worker thread from our pool.
     * Used by Mixins to decide whether to defer unsafe world operations.
     */
    public static boolean isEntityThread() {
        return Thread.currentThread().getName().startsWith(THREAD_NAME_PREFIX);
    }

    /**
     * Reinitialize thread pool and blacklist (called on config change).
     */
    public static void reinitialize() {
        initThreadPool();
        rebuildBlacklist();
    }

    /**
     * Shutdown the thread pool (called on server stop).
     */
    public static void shutdown() {
        if (threadPool != null) {
            threadPool.shutdown();
            try {
                if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    threadPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                threadPool.shutdownNow();
            }
        }
        groups.clear();
        allEntities.clear();
        mainThreadEntities.clear();
        DeferredActionQueue.clear();
    }

    // Simple data holder — no extra allocations
    private static class EntityTickEntry {
        final World world;
        final Entity entity;

        EntityTickEntry(World world, Entity entity) {
            this.world = world;
            this.entity = entity;
        }
    }
}
