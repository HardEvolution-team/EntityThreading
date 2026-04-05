package ded.entitythreading.schedule;

import ded.entitythreading.mixin.PathFinderAccessor;
import net.minecraft.entity.EntityLiving;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathFinder;
import net.minecraft.world.IBlockAccess;

import java.util.concurrent.*;

public final class AsyncPathProcessor {

    private static final ConcurrentHashMap<Integer, CompletableFuture<Path>> pendingPaths = new ConcurrentHashMap<>(256);
    private static final Semaphore pathSemaphore = new Semaphore(8);

    public static Path pollCompleted(int entityId) {
        CompletableFuture<Path> future = pendingPaths.get(entityId);
        if (future == null || !future.isDone()) return null;
        pendingPaths.remove(entityId, future);
        try {
            return future.getNow(null);
        } catch (CompletionException | CancellationException e) {
            return null;
        }
    }

    public static boolean isPending(int entityId) {
        CompletableFuture<Path> future = pendingPaths.get(entityId);
        return future != null && !future.isDone();
    }

    public static void submitPathRequest(int entityId, PathFinder pathFinder,
                                         IBlockAccess worldCache, EntityLiving entity,
                                         double x, double y, double z, float range) {
        ExecutorService pool = EntityTickScheduler.getSharedPool();
        if (pool == null || pool.isShutdown()) return;
        if (!pathSemaphore.tryAcquire()) return;

        CompletableFuture<Path> future = CompletableFuture.supplyAsync(() -> {
            try {
                return ((PathFinderAccessor) pathFinder)
                        .invokeFindPath(worldCache, entity, x, y, z, range);
            } catch (Exception e) {
                return null;
            } finally {
                pathSemaphore.release();
            }
        }, pool);

        pendingPaths.put(entityId, future);
    }

    public static void removeEntity(int entityId) {
        CompletableFuture<Path> future = pendingPaths.remove(entityId);
        if (future != null) future.cancel(false);
    }

    public static void clearAll() {
        for (CompletableFuture<Path> f : pendingPaths.values()) f.cancel(false);
        pendingPaths.clear();
    }

    public static void shutdown() {
        clearAll();
    }
}
