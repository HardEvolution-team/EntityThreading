package ded.otherthreading.schedule;

import ded.otherthreading.OtherThreadingConfig;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.*;
import java.util.concurrent.*;

/**
 * Parallel scheduler for Blocks and TileEntities.
 * Optimized for high-throughput world ticking.
 */
public class BlockTickScheduler {

    private static volatile ExecutorService threadPool;
    private static int currentThreadCount = 0;

    private static final ThreadLocal<List<NextTickListEntry>> parallelBlockTicks = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<List<NextTickListEntry>> parallelRandomTicks = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<List<TileEntity>> parallelTileEntities = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<List<RandomTickTask>> parallelRandomTickTasks = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<Boolean> IS_WORKER_THREAD = ThreadLocal.withInitial(() -> false);

    private static volatile Map<Long, Chunk> chunkSnapshot = Collections.emptyMap();

    private static final String THREAD_NAME_PREFIX = "OtherThreading-Worker-";
    private static final long WORKER_TIMEOUT_MS = 2000;

    static {
        initThreadPool();
    }

    private static void initThreadPool() {
        int threads = OtherThreadingConfig.threadCount;
        if (threads == currentThreadCount && threadPool != null && !threadPool.isShutdown()) {
            return;
        }
        if (threadPool != null) {
            threadPool.shutdownNow();
        }

        threadPool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r);
            t.setName(THREAD_NAME_PREFIX + t.getId());
            t.setDaemon(true);
            return t;
        });

        currentThreadCount = threads;
    }

    public static void queueBlockTick(NextTickListEntry entry) {
        parallelBlockTicks.get().add(entry);
    }

    public static void queueRandomTick(NextTickListEntry entry) {
        parallelRandomTicks.get().add(entry);
    }

    public static void queueTileEntity(TileEntity te) {
        parallelTileEntities.get().add(te);
    }

    public static void queueRandomTickTask(Chunk chunk, int speed) {
        parallelRandomTickTasks.get().add(new RandomTickTask(chunk, speed));
    }

    public static void waitForFinish(World world) {
        List<NextTickListEntry> blockTicks = parallelBlockTicks.get();
        List<NextTickListEntry> randomTicks = parallelRandomTicks.get();
        List<TileEntity> tileEntities = parallelTileEntities.get();
        List<RandomTickTask> randomTasks = parallelRandomTickTasks.get();

        if (blockTicks.isEmpty() && randomTicks.isEmpty() && tileEntities.isEmpty() && randomTasks.isEmpty()) return;

        initThreadPool();
        buildChunkSnapshot(world);

        int threads = currentThreadCount;
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final int tIndex = i;
            threadPool.execute(() -> {
                IS_WORKER_THREAD.set(true);
                try {
                    // Split scheduled ticks
                    splitAndProcess(blockTicks, tIndex, threads, entry -> tickBlockSafe(world, entry, false));
                    // Split individual random ticks
                    splitAndProcess(randomTicks, tIndex, threads, entry -> tickBlockSafe(world, entry, true));
                    // Split tile entities
                    splitAndProcess(tileEntities, tIndex, threads, te -> tickTESafe(te));
                    // Split chunk random tick tasks
                    splitAndProcess(randomTasks, tIndex, threads, task -> processRandomTicksForChunk(world, task));
                } finally {
                    IS_WORKER_THREAD.set(false);
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(WORKER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        chunkSnapshot = Collections.emptyMap();
        blockTicks.clear();
        randomTicks.clear();
        tileEntities.clear();
        randomTasks.clear();
        
        ParallelLightingEngine.process(world);
        OtherDeferredActionQueue.replayAll(world);
    }

    private static <T> void splitAndProcess(List<T> list, int threadIndex, int totalThreads, java.util.function.Consumer<T> processor) {
        int size = list.size();
        if (size == 0) return;
        int perThread = (size + totalThreads - 1) / totalThreads;
        int start = threadIndex * perThread;
        int end = Math.min(start + perThread, size);
        for (int i = start; i < end; i++) {
            processor.accept(list.get(i));
        }
    }

    private static void buildChunkSnapshot(World world) {
        if (world.isRemote) return;
        HashMap<Long, Chunk> snapshot = new HashMap<>(1024);
        try {
            net.minecraft.world.gen.ChunkProviderServer provider = (net.minecraft.world.gen.ChunkProviderServer) world.getChunkProvider();
            for (Chunk chunk : provider.getLoadedChunks()) {
                snapshot.put(ChunkPos.asLong(chunk.x, chunk.z), chunk);
            }
        } catch (Exception e) {}
        chunkSnapshot = snapshot;
    }

    public static Chunk getChunkFromSnapshot(int x, int z) {
        return chunkSnapshot.get(ChunkPos.asLong(x, z));
    }

    private static void tickBlockSafe(World world, NextTickListEntry entry, boolean isRandom) {
        try {
            IBlockState state = world.getBlockState(entry.position);
            if (state.getBlock() == entry.getBlock()) {
                if (isRandom) {
                    state.getBlock().randomTick(world, entry.position, state, world.rand);
                } else {
                    state.getBlock().updateTick(world, entry.position, state, world.rand);
                }
            }
        } catch (Exception e) {}
    }

    private static void tickTESafe(TileEntity te) {
        try {
            if (te instanceof net.minecraft.util.ITickable) {
                ((net.minecraft.util.ITickable) te).update();
            }
        } catch (Exception e) {}
    }

    private static void processRandomTicksForChunk(World world, RandomTickTask task) {
        Chunk chunk = task.chunk;
        int speed = task.speed;
        for (ExtendedBlockStorage storage : chunk.getBlockStorageArray()) {
            if (storage != Chunk.NULL_BLOCK_STORAGE && storage.needsRandomTick()) {
                for (int i = 0; i < speed; i++) {
                    int x = world.rand.nextInt(16);
                    int y = world.rand.nextInt(16);
                    int z = world.rand.nextInt(16);
                    IBlockState state = storage.get(x, y, z);
                    if (state.getBlock().getTickRandomly()) {
                        state.getBlock().randomTick(world, new BlockPos(x + chunk.x * 16, y + storage.getYLocation(), z + chunk.z * 16), state, world.rand);
                    }
                }
            }
        }
    }

    public static boolean isWorkerThread() {
        return IS_WORKER_THREAD.get();
    }

    public static void reinitialize() {
        initThreadPool();
    }

    private static class RandomTickTask {
        final Chunk chunk;
        final int speed;
        RandomTickTask(Chunk chunk, int speed) { this.chunk = chunk; this.speed = speed; }
    }
}
