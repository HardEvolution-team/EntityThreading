package ded.tileentitythreading.schedule;

import ded.tileentitythreading.TileEntityThreadingConfig;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-performance parallel tile entity tick scheduler.
 *
 * Architecture (optimized for 60,000+ tile entities at 20 TPS):
 * 1. ForkJoinPool with work-stealing — threads that finish early steal tasks from busy ones
 * 2. Zero-allocation per tick — pre-allocated ITickable[] buffers, no TileTickEntry objects
 * 3. Cached chunk snapshot — rebuilt only when chunks load/unload, not every tick
 * 4. Class-based blacklist — O(1) identity lookup, no getName() overhead
 * 5. RecursiveAction for optimal fork/join splitting of tile entity batches
 */
public class TileEntityTickScheduler {

    private static volatile ForkJoinPool forkJoinPool;
    private static int currentThreadCount = 0;

    // --- Class-based blacklist (avoids getName() for 60k tiles/tick) ---
    private static final Set<Class<?>> blacklistedClassSet = ConcurrentHashMap.newKeySet();
    private static final Set<String> blacklistedClassNames = ConcurrentHashMap.newKeySet();
    private static final Set<String> loggedErrorClasses = ConcurrentHashMap.newKeySet();

    // --- Pre-allocated buffers (zero-alloc per tick) ---
    // Main thread only — no synchronization needed
    private static ITickable[] parallelBuffer = new ITickable[4096];
    private static int parallelCount = 0;
    private static ITickable[] mainThreadBuffer = new ITickable[512];
    private static int mainThreadCount = 0;
    private static World currentWorld = null;

    // ThreadLocal flag: true if current thread is one of our tile entity worker threads
    private static final ThreadLocal<Boolean> IS_WORKER_THREAD = ThreadLocal.withInitial(() -> false);

    // Track current side for deferred action queue routing
    private static final ThreadLocal<Boolean> currentSideIsRemote = ThreadLocal.withInitial(() -> false);

    /**
     * Cached chunk snapshot — rebuilt ONLY when chunks load/unload.
     * Worker threads read from this instead of ChunkProvider (NOT thread-safe).
     */
    private static volatile Map<Long, Chunk> chunkSnapshot = Collections.emptyMap();
    static final AtomicBoolean snapshotDirty = new AtomicBoolean(true);

    private static final String THREAD_NAME_PREFIX = "TileEntityThreading-Worker-";

    // Safety timeout — 3 seconds is long enough for any reasonable tile entity tick
    private static final long WORKER_TIMEOUT_MS = 3_000;

    // Cooldown after timeout — disable parallel ticking temporarily
    private static volatile long cooldownUntil = 0;
    private static final long COOLDOWN_DURATION_MS = 30_000;

    // --- Performance counters for debug logging ---
    private static long lastLogTime = 0;
    private static long ticksSinceLog = 0;
    private static long totalParallelTicked = 0;
    private static long totalMainThreadTicked = 0;
    private static long totalDeferredActions = 0;

    static {
        initThreadPool();
        rebuildBlacklist();
    }

    private static void initThreadPool() {
        int threads = TileEntityThreadingConfig.getEffectiveThreadCount();
        if (threads == currentThreadCount && forkJoinPool != null && !forkJoinPool.isShutdown()) {
            return;
        }
        if (forkJoinPool != null) {
            forkJoinPool.shutdownNow();
        }

        forkJoinPool = new ForkJoinPool(
                threads,
                pool -> {
                    ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                    t.setName(THREAD_NAME_PREFIX + t.getPoolIndex());
                    t.setDaemon(true);
                    return t;
                },
                (t, e) -> System.err.println("[TileEntityThreading] Uncaught error in " + t.getName() + ": " + e.getMessage()),
                true // asyncMode — better for non-recursive tasks
        );

        currentThreadCount = threads;
        System.out.println("[TileEntityThreading] ForkJoinPool: " + threads + " workers (work-stealing enabled)");
    }

    public static void rebuildBlacklist() {
        blacklistedClassSet.clear();
        blacklistedClassNames.clear();

        if (TileEntityThreadingConfig.blacklistedTileEntities != null) {
            for (String cls : TileEntityThreadingConfig.blacklistedTileEntities) {
                if (cls != null && !cls.trim().isEmpty()) {
                    String name = cls.trim();
                    blacklistedClassNames.add(name);
                    try {
                        blacklistedClassSet.add(Class.forName(name));
                    } catch (ClassNotFoundException ignored) {
                        // Class not loaded yet — will be resolved lazily
                    }
                }
            }
        }
    }

    /**
     * Fast blacklist check — uses Class identity (O(1), no string alloc).
     * Falls back to class name check for classes not yet resolved.
     */
    private static boolean isBlacklisted(Class<?> clazz) {
        if (blacklistedClassSet.contains(clazz)) {
            return true;
        }
        // Lazy resolve: check by name, then cache the Class reference
        if (!blacklistedClassNames.isEmpty()) {
            String name = clazz.getName();
            if (blacklistedClassNames.contains(name)) {
                blacklistedClassSet.add(clazz);
                return true;
            }
        }
        return false;
    }

    /**
     * Called by ASM hook — replaces the vanilla ITickable.update() call.
     * Queues the tile entity for parallel or main-thread ticking.
     * ZERO ALLOCATION — uses pre-allocated arrays.
     */
    public static void queueTileEntity(World world, ITickable tickable) {
        if (!TileEntityThreadingConfig.enabled || world.isRemote) {
            tickable.update();
            return;
        }

        currentWorld = world;

        // Non-TileEntity ITickable or blacklisted → main thread
        if (!(tickable instanceof TileEntity) || isBlacklisted(tickable.getClass())) {
            if (mainThreadCount >= mainThreadBuffer.length) {
                mainThreadBuffer = Arrays.copyOf(mainThreadBuffer, mainThreadBuffer.length * 2);
            }
            mainThreadBuffer[mainThreadCount++] = tickable;
            return;
        }

        // Parallel buffer — grow if needed (rare, only first few ticks)
        if (parallelCount >= parallelBuffer.length) {
            parallelBuffer = Arrays.copyOf(parallelBuffer, parallelBuffer.length * 2);
        }
        parallelBuffer[parallelCount++] = tickable;
    }

    /**
     * Called by ASM hook at the end of World.updateEntities().
     * Ticks all queued tile entities and replays deferred actions.
     */
    public static void waitForFinish() {
        if (!TileEntityThreadingConfig.enabled) {
            return;
        }

        int pCount = parallelCount;
        int mCount = mainThreadCount;

        if (pCount == 0 && mCount == 0) {
            return;
        }

        World world = currentWorld;
        boolean isRemote = world != null && world.isRemote;

        // 1. Tick main-thread-only tiles first (blacklisted, non-TileEntity ITickable)
        if (mCount > 0) {
            tickMainThreadBuffer(mCount);
        }

        // 2. Parallel tick (or main-thread-only if below threshold or in cooldown)
        if (pCount > 0) {
            boolean inCooldown = System.currentTimeMillis() < cooldownUntil;
            if (pCount < TileEntityThreadingConfig.minTilesForThreading || inCooldown) {
                tickParallelBufferOnMainThread(pCount);
            } else {
                tickParallel(world, pCount, isRemote);
            }
        }

        // 3. Replay ALL deferred world mutations on main thread
        int deferred = TileEntityDeferredQueue.replayAll(isRemote);

        // 4. Debug logging (aggregated, not per-tick)
        if (TileEntityThreadingConfig.debugLogging) {
            logPerformance(pCount, mCount, deferred);
        }

        // 5. Reset state for next tick
        parallelCount = 0;
        mainThreadCount = 0;
        currentWorld = null;
    }

    private static void tickMainThreadBuffer(int count) {
        for (int i = 0; i < count; i++) {
            ITickable tickable = mainThreadBuffer[i];
            mainThreadBuffer[i] = null; // allow GC
            try {
                tickable.update();
            } catch (Exception e) {
                if (loggedErrorClasses.add(tickable.getClass().getName())) {
                    System.err.println("[TileEntityThreading] Main thread tick error: " +
                            tickable.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }
    }

    private static void tickParallelBufferOnMainThread(int count) {
        for (int i = 0; i < count; i++) {
            ITickable tickable = parallelBuffer[i];
            parallelBuffer[i] = null;
            try {
                tickable.update();
            } catch (Exception e) {
                if (loggedErrorClasses.add(tickable.getClass().getName())) {
                    System.err.println("[TileEntityThreading] Tick error: " +
                            tickable.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Rebuild chunk snapshot if dirty (chunks loaded/unloaded since last rebuild).
     * Called on main thread before starting workers.
     */
    private static void rebuildChunkSnapshotIfDirty(World world) {
        if (!snapshotDirty.compareAndSet(true, false)) {
            return; // Snapshot is still valid — zero cost
        }

        HashMap<Long, Chunk> snapshot = new HashMap<>(1024);
        try {
            ChunkProviderServer provider = (ChunkProviderServer) world.getChunkProvider();
            for (Chunk chunk : provider.getLoadedChunks()) {
                snapshot.put(ChunkPos.asLong(chunk.x, chunk.z), chunk);
            }
        } catch (Exception e) {
            System.err.println("[TileEntityThreading] Failed to build chunk snapshot: " + e.getMessage());
            snapshotDirty.set(true); // retry next tick
        }
        chunkSnapshot = snapshot;
    }

    /**
     * Mark the chunk snapshot as dirty — called by event handlers when chunks load/unload.
     */
    public static void markSnapshotDirty() {
        snapshotDirty.set(true);
    }

    /**
     * Get a chunk from the pre-built snapshot. Used by mixins for worker threads.
     */
    public static Chunk getChunkFromSnapshot(int x, int z) {
        return chunkSnapshot.get(ChunkPos.asLong(x, z));
    }

    /**
     * Core parallel ticking using ForkJoinPool with work-stealing.
     * RecursiveAction splits the tile array recursively for optimal load balancing.
     */
    private static void tickParallel(World world, int count, boolean isRemote) {
        // Rebuild chunk snapshot only if dirty
        rebuildChunkSnapshotIfDirty(world);

        // Snapshot the buffer segment (workers must not see buffer mutations)
        ITickable[] snapshot = new ITickable[count];
        System.arraycopy(parallelBuffer, 0, snapshot, 0, count);
        // Clear references for GC immediately
        Arrays.fill(parallelBuffer, 0, count, null);

        // Determine fork threshold: aim for ~4x overpartitioning for work-stealing
        int forkThreshold = Math.max(
                TileEntityThreadingConfig.minBatchSize,
                count / (currentThreadCount * 4)
        );

        // Submit the recursive task to ForkJoinPool
        TileTickTask rootTask = new TileTickTask(snapshot, 0, count, forkThreshold, isRemote);

        try {
            forkJoinPool.execute(rootTask);
            // Wait with timeout
            rootTask.get(WORKER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            System.err.println("[TileEntityThreading] WARNING: Workers stuck after " + WORKER_TIMEOUT_MS + "ms!");
            System.err.println("[TileEntityThreading] Active threads: " + forkJoinPool.getActiveThreadCount() +
                    ", queued tasks: " + forkJoinPool.getQueuedTaskCount());
            // Dump active worker stacks
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getName().startsWith(THREAD_NAME_PREFIX)) {
                    System.err.println("[TileEntityThreading] Stuck thread: " + t.getName());
                    for (StackTraceElement ste : t.getStackTrace()) {
                        System.err.println("    at " + ste);
                    }
                }
            }
            // Cancel and cooldown
            rootTask.cancel(true);
            cooldownUntil = System.currentTimeMillis() + COOLDOWN_DURATION_MS;
            System.err.println("[TileEntityThreading] Parallel ticking disabled for " +
                    (COOLDOWN_DURATION_MS / 1000) + "s cooldown.");
        } catch (Exception e) {
            System.err.println("[TileEntityThreading] Parallel tick error: " + e.getMessage());
        }
    }

    /**
     * ForkJoinTask that recursively splits tile entity arrays for work-stealing.
     * Below the threshold, ticks tiles directly.
     */
    private static final class TileTickTask extends RecursiveAction {
        private final ITickable[] tiles;
        private final int start;
        private final int end;
        private final int threshold;
        private final boolean isRemote;

        TileTickTask(ITickable[] tiles, int start, int end, int threshold, boolean isRemote) {
            this.tiles = tiles;
            this.start = start;
            this.end = end;
            this.threshold = threshold;
            this.isRemote = isRemote;
        }

        @Override
        protected void compute() {
            int length = end - start;

            if (length <= threshold) {
                // Base case: tick directly
                IS_WORKER_THREAD.set(true);
                currentSideIsRemote.set(isRemote);
                try {
                    for (int i = start; i < end; i++) {
                        ITickable tickable = tiles[i];
                        if (tickable != null) {
                            try {
                                tickable.update();
                            } catch (Throwable t) {
                                if (loggedErrorClasses.add(tickable.getClass().getName())) {
                                    System.err.println("[TileEntityThreading] " +
                                            tickable.getClass().getSimpleName() +
                                            " tick error (continuing): " + t.getMessage());
                                }
                            }
                        }
                    }
                } finally {
                    IS_WORKER_THREAD.set(false);
                }
                return;
            }

            // Fork: split into two halves
            int mid = start + (length >>> 1);
            TileTickTask left = new TileTickTask(tiles, start, mid, threshold, isRemote);
            TileTickTask right = new TileTickTask(tiles, mid, end, threshold, isRemote);
            left.fork();
            right.compute(); // compute right half in current thread
            left.join();     // wait for left half
        }
    }

    /**
     * Returns true if current thread is a worker thread from our tile entity pool.
     */
    public static boolean isTileEntityThread() {
        return IS_WORKER_THREAD.get();
    }

    /**
     * Returns the side (Client vs Server) the current thread is processing.
     */
    public static boolean isCurrentThreadRemote() {
        return currentSideIsRemote.get();
    }

    public static void reinitialize() {
        initThreadPool();
        rebuildBlacklist();
        markSnapshotDirty();
    }

    public static void shutdown() {
        if (forkJoinPool != null) {
            forkJoinPool.shutdown();
            try {
                if (!forkJoinPool.awaitTermination(3, TimeUnit.SECONDS)) {
                    forkJoinPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                forkJoinPool.shutdownNow();
            }
        }
        parallelCount = 0;
        mainThreadCount = 0;
        currentWorld = null;
        TileEntityDeferredQueue.clear();
        chunkSnapshot = Collections.emptyMap();
        snapshotDirty.set(true);
    }

    /**
     * Aggregated debug logging — prints summary every 5 seconds instead of per-tick.
     */
    private static void logPerformance(int pCount, int mCount, int deferred) {
        ticksSinceLog++;
        totalParallelTicked += pCount;
        totalMainThreadTicked += mCount;
        totalDeferredActions += deferred;

        long now = System.currentTimeMillis();
        if (now - lastLogTime >= 5_000) {
            double avgParallel = (double) totalParallelTicked / ticksSinceLog;
            double avgMainThread = (double) totalMainThreadTicked / ticksSinceLog;
            double avgDeferred = (double) totalDeferredActions / ticksSinceLog;
            System.out.println(String.format(
                    "[TileEntityThreading] %d ticks: avg %.0f parallel, %.0f main-thread, %.0f deferred | Pool: %d workers, %d active",
                    ticksSinceLog, avgParallel, avgMainThread, avgDeferred,
                    currentThreadCount, forkJoinPool != null ? forkJoinPool.getActiveThreadCount() : 0
            ));
            lastLogTime = now;
            ticksSinceLog = 0;
            totalParallelTicked = 0;
            totalMainThreadTicked = 0;
            totalDeferredActions = 0;
        }
    }
}
