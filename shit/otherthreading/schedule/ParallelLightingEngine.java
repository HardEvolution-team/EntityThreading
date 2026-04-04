package ded.otherthreading.schedule;

import ded.otherthreading.OtherThreadingConfig;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Parallel Lighting Engine for 1.12.2.
 */
public class ParallelLightingEngine {

    private static final ConcurrentLinkedQueue<LightTask> queue = new ConcurrentLinkedQueue<>();
    private static volatile ExecutorService pool;
    private static int currentThreads = 0;

    private static void initPool() {
        int threads = OtherThreadingConfig.threadCount;
        if (pool != null && currentThreads == threads) return;
        if (pool != null) pool.shutdownNow();
        pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "OtherThreading-Lighting-Worker");
            t.setDaemon(true);
            return t;
        });
        currentThreads = threads;
    }

    public static void enqueue(BlockPos pos, EnumSkyBlock type) {
        queue.add(new LightTask(pos, type));
    }

    public static void process(World world) {
        if (queue.isEmpty()) return;
        initPool();

        int threads = currentThreads;
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    LightTask task;
                    while ((task = queue.poll()) != null) {
                        try {
                            world.checkLightFor(task.type, task.pos);
                        } catch (Exception e) {}
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class LightTask {
        final BlockPos pos;
        final EnumSkyBlock type;
        LightTask(BlockPos pos, EnumSkyBlock type) {
            this.pos = pos;
            this.type = type;
        }
    }
}
