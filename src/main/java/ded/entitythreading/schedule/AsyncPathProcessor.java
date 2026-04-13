package ded.entitythreading.schedule;

import ded.entitythreading.EntityThreadingMod;
import ded.entitythreading.mixin.PathFinderAccessor;
import net.minecraft.entity.EntityLiving;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathFinder;
import net.minecraft.world.IBlockAccess;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages asynchronous pathfinding requests.
 * Each entity can have at most one pending path computation at a time.
 * An atomic counter limits concurrent pathfinding to prevent thread pool saturation.
 *
 * Key design decisions:
 * - AtomicInteger instead of Semaphore: no blocking, no leak risk on cancel
 * - putIfAbsent prevents duplicate submissions
 * - Future is registered BEFORE submission to the pool
 * - Snapshot of entity position is captured at submission time
 */
public final class AsyncPathProcessor {

    private static final int MAX_CONCURRENT_PATHS = 8;

    private static final ConcurrentHashMap<Integer, CompletableFuture<Path>> pendingPaths =
            new ConcurrentHashMap<>(256);

    // Atomic counter instead of Semaphore — no leak risk on cancel/exception
    private static final AtomicInteger activePaths = new AtomicInteger(0);

    private AsyncPathProcessor() {}

    /**
     * Polls for a completed path result. Returns null if no result is ready.
     * Atomically removes the future only if it's the same instance (ABA-safe).
     */
    public static Path pollCompleted(int entityId) {
        CompletableFuture<Path> future = pendingPaths.get(entityId);
        if (future == null || !future.isDone()) {
            return null;
        }
        // Atomic remove — only if the future hasn't been replaced by a new submission
        if (!pendingPaths.remove(entityId, future)) {
            return null;
        }
        try {
            return future.getNow(null);
        } catch (CompletionException | CancellationException e) {
            EntityThreadingMod.LOGGER.debug("Path computation failed for entity {}: {}",
                    entityId, e.getMessage());
            return null;
        }
    }

    public static boolean isPending(int entityId) {
        CompletableFuture<Path> future = pendingPaths.get(entityId);
        return future != null && !future.isDone();
    }

    /**
     * Submits an async pathfinding request.
     *
     * Critical fix: the future is created and registered via putIfAbsent BEFORE
     * the async computation starts. This prevents the race where pollCompleted
     * runs between supplyAsync starting and pendingPaths.put completing.
     *
     * @param entityId   unique entity ID (used as dedup key)
     * @param pathFinder the PathFinder instance (accessed via mixin invoker)
     * @param worldCache a SNAPSHOT of the world (ChunkCache) — must be created by caller
     * @param entity     the entity requesting the path
     * @param x          target X
     * @param y          target Y
     * @param z          target Z
     * @param range      search range
     */
    public static void submitPathRequest(int entityId, PathFinder pathFinder,
                                         IBlockAccess worldCache, EntityLiving entity,
                                         double x, double y, double z, float range) {
        ExecutorService pool = EntityTickScheduler.getSharedPool();
        if (pool == null || pool.isShutdown()) {
            return;
        }

        // Non-blocking concurrency limit — no semaphore leak possible
        int current = activePaths.get();
        if (current >= MAX_CONCURRENT_PATHS) {
            return;
        }
        // CAS to atomically claim a slot; if another thread beats us, retry is not worth it
        if (!activePaths.compareAndSet(current, current + 1)) {
            return;
        }

        // Create future FIRST, register it, THEN submit to pool
        CompletableFuture<Path> future = new CompletableFuture<>();

        // putIfAbsent: if there's already a pending future, don't overwrite it
        CompletableFuture<Path> existing = pendingPaths.putIfAbsent(entityId, future);
        if (existing != null) {
            // Already has a pending request — release the slot
            activePaths.decrementAndGet();
            return;
        }

        // Now submit the actual computation
        pool.execute(() -> {
            try {
                Path result = ((PathFinderAccessor) pathFinder)
                        .invokeFindPath(worldCache, entity, x, y, z, range);
                future.complete(result);
            } catch (Exception e) {
                EntityThreadingMod.LOGGER.debug("Async pathfinding error for entity {}: {}",
                        entityId, e.getMessage());
                future.complete(null); // complete with null, not completeExceptionally
            } finally {
                activePaths.decrementAndGet();
            }
        });
    }

    public static void removeEntity(int entityId) {
        CompletableFuture<Path> future = pendingPaths.remove(entityId);
        if (future != null && !future.isDone()) {
            future.cancel(false);
            // Note: if the task is already running, cancel(false) won't interrupt it,
            // but the activePaths counter will be decremented in the finally block
        }
    }

    public static void clearAll() {
        pendingPaths.forEach((_, f) -> f.cancel(false));
        pendingPaths.clear();
        activePaths.set(0);
    }

    public static void shutdown() {
        clearAll();
    }

    /** For monitoring/debug */
    public static int getActiveCount() {
        return activePaths.get();
    }

    public static int getPendingCount() {
        return pendingPaths.size();
    }
}
