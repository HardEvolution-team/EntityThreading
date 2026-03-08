package ded.entitythreading.schedule;

import ded.entitythreading.EntityThreadingConfig;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathFinder;
import net.minecraft.world.IBlockAccess;
import net.minecraft.entity.EntityLiving;

import java.util.concurrent.*;

/**
 * Manages asynchronous pathfinding computation.
 *
 * Uses the shared thread pool from EntityTickScheduler instead of its own.
 * Limits concurrent path computations to avoid overwhelming the pool.
 *
 * Safety guarantees:
 * - ChunkCache (IBlockAccess) is a snapshot, safe to read from any thread
 * - Each entity gets at most one in-flight computation
 * - Main thread is NEVER blocked
 * - Results available on next tick (1-tick delay, imperceptible)
 */
public final class AsyncPathProcessor {

    // Entity ID -> in-flight path computation
    private static final ConcurrentHashMap<Integer, CompletableFuture<Path>> pendingPaths = new ConcurrentHashMap<>(256);

    // Entity ID -> completed path result
    private static final ConcurrentHashMap<Integer, Path> completedPaths = new ConcurrentHashMap<>(256);

    // Limit concurrent path computations to prevent pool starvation
    private static final java.util.concurrent.Semaphore pathSemaphore = new java.util.concurrent.Semaphore(8);

    /**
     * Check if there's a completed path for this entity.
     * @return completed Path, or null if not ready yet
     */
    public static Path pollCompleted(int entityId) {
        Path result = completedPaths.remove(entityId);
        if (result != null) {
            return result;
        }

        CompletableFuture<Path> future = pendingPaths.get(entityId);
        if (future != null && future.isDone()) {
            pendingPaths.remove(entityId);
            try {
                return future.getNow(null);
            } catch (CompletionException | CancellationException e) {
                return null;
            }
        }

        return null;
    }

    /**
     * Returns true if there is a pending computation for this entity.
     */
    public static boolean isPending(int entityId) {
        CompletableFuture<Path> future = pendingPaths.get(entityId);
        return future != null && !future.isDone();
    }

    /**
     * Submit an async path computation.
     */
    public static void submitPathRequest(int entityId, PathFinder pathFinder,
            IBlockAccess worldCache, EntityLiving entity,
            double x, double y, double z, float range) {
        ExecutorService pool = EntityTickScheduler.getSharedPool();
        if (pool == null || pool.isShutdown()) return;

        // Don't exceed concurrent path limit
        if (!pathSemaphore.tryAcquire()) return;

        final double fx = x, fy = y, fz = z;
        final float fr = range;

        CompletableFuture<Path> future = CompletableFuture.supplyAsync(() -> {
            try {
                return ((ded.entitythreading.transform.mixin.accessor.PathFinderAccessor) pathFinder)
                        .invokeFindPath(worldCache, entity, fx, fy, fz, fr);
            } catch (Exception e) {
                if (EntityThreadingConfig.debugLogging) {
                    System.err.println("[EntityThreading] Async pathfinding error for " +
                            entity.getClass().getSimpleName() + ": " + e.getMessage());
                }
                return null;
            } finally {
                pathSemaphore.release();
            }
        }, pool);

        pendingPaths.put(entityId, future);
    }

    /**
     * Remove cached/pending results for an entity.
     */
    public static void removeEntity(int entityId) {
        completedPaths.remove(entityId);
        CompletableFuture<Path> future = pendingPaths.remove(entityId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * Clear all state. Called on server stop.
     */
    public static void clearAll() {
        pendingPaths.values().forEach(f -> f.cancel(false));
        pendingPaths.clear();
        completedPaths.clear();
    }

    /**
     * Shutdown — just clear state, pool is managed by EntityTickScheduler.
     */
    public static void shutdown() {
        clearAll();
    }
}
