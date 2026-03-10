package ded.chunkgenthreading;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.IChunkGenerator;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Parallel chunk generation scheduler — BATCH CONCURRENT model.
 *
 * How it works:
 * ─────────────
 * Main thread requests chunk [x,z] via provideChunk():
 *   1. We check if we already have a CompletableFuture for [x,z]
 *   2. If not, we scan all chunks in a look-ahead radius around [x,z] that
 *      are NOT yet loaded, and submit them ALL to the ForkJoinPool simultaneously
 *   3. We then call future.get(timeout) for [x,z] to block only until IT is ready
 *   4. Meanwhile, worker threads generate all the other chunks in parallel
 *   5. When main thread asks for neighbors next tick, their futures are already done —
 *      we just collect the result without any generation wait
 *
 * Why generateChunk() is thread-safe:
 * ────────────────────────────────────
 * Vanilla/Forge generators (BiomeDecorator, ChunkProviderOverworld, etc.) all work
 * on a fresh ChunkPrimer which is stack-local. The resulting Chunk object is also
 * new and not touched by anyone else until we hand it to main thread via the future.
 * No World state is mutated during generateChunk() in vanilla generators.
 *
 * Modded generators that DO touch World:
 * ────────────────────────────────────────
 * generateChunk() is supposed to only create the Chunk from ChunkPrimer.
 * The populate() step is where World mutations happen (decoration, structures).
 * populate() is NOT called during generateChunk() — it's called AFTER by
 * ChunkProviderServer.provideChunk() on the main thread, so it's always safe.
 * If a badly written modded generator calls World in generateChunk(), that is
 * already broken in vanilla single-threaded mode. We don't protect against that.
 *
 * Compatibility:
 * ──────────────
 * - Phosphor / Starlight: We don't touch World.checkLight() — that's populate()
 * - Alfheim: Same — light calc happens in populate(), not generateChunk()
 * - Cubic Chunks: uses a completely different chunk system — our mixin won't fire
 * - All Forge events: ChunkEvent.Load fires AFTER we return, on main thread
 */
public class ParallelChunkGenScheduler {

    private static final String THREAD_NAME_PREFIX = "ChunkGen-Worker-";
    private static volatile ForkJoinPool pool;
    private static int poolSize = 0;

    // Maps chunk key → future for its generated chunk
    // CompletableFuture<Chunk> — completed by a worker thread, consumed by main thread
    private static final ConcurrentHashMap<Long, CompletableFuture<Chunk>> futures =
            new ConcurrentHashMap<>(128);

    // Worker thread marker
    static final ThreadLocal<Boolean> IS_WORKER = ThreadLocal.withInitial(() -> false);

    // Stats
    private static final AtomicLong parallelHits = new AtomicLong();
    private static final AtomicLong mainThreadGens = new AtomicLong();
    private static long lastStatLog = 0;

    // Cooldown after a timeout
    private static volatile long cooldownUntil = 0;

    static {
        createPool();
    }

    private static void createPool() {
        int threads = ChunkGenThreadingConfig.getEffectiveThreadCount();
        if (threads == poolSize && pool != null && !pool.isShutdown()) return;
        if (pool != null) pool.shutdownNow();

        pool = new ForkJoinPool(
                threads,
                p -> {
                    ForkJoinWorkerThread t =
                            ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(p);
                    t.setName(THREAD_NAME_PREFIX + t.getPoolIndex());
                    t.setDaemon(true);
                    return t;
                },
                (t, e) -> System.err.println("[ChunkGen] Error in " + t.getName() + ": " + e),
                true   // asyncMode — no thread blocking each other
        );
        poolSize = threads;
        System.out.println("[ChunkGenThreading] Pool started: " + threads + " workers.");
    }

    public static boolean isWorkerThread() {
        return IS_WORKER.get();
    }

    /**
     * Called from MixinChunkProviderServer @Redirect.
     * Replaces the generateChunk(x, z) call inside provideChunk().
     *
     * Returns the generated chunk. Either fetched from a completed parallel
     * future, or generated right now (falls back to vanilla if disabled/cooldown).
     */
    public static Chunk generateOrGet(IChunkGenerator generator,
                                      ChunkProviderServer provider,
                                      int x, int z) {

        // Disabled or cooldown → vanilla
        if (!ChunkGenThreadingConfig.enabled) {
            return generator.generateChunk(x, z);
        }
        if (System.currentTimeMillis() < cooldownUntil) {
            return generator.generateChunk(x, z);
        }

        // Submit batch of pending chunks around this position to the pool
        submitBatch(generator, provider, x, z);

        // Get the future for the specific chunk we need
        long key = key(x, z);
        CompletableFuture<Chunk> future = futures.get(key);

        if (future != null) {
            try {
                Chunk chunk = future.get(ChunkGenThreadingConfig.chunkGenTimeoutMs, TimeUnit.MILLISECONDS);
                futures.remove(key);
                parallelHits.incrementAndGet();

                if (ChunkGenThreadingConfig.debugLogging) {
                    logStats();
                }

                return chunk;
            } catch (TimeoutException e) {
                System.err.println("[ChunkGenThreading] TIMEOUT waiting for chunk [" + x + "," + z + "]");
                futures.remove(key);
                // enter cooldown and fall through to vanilla
                cooldownUntil = System.currentTimeMillis() + ChunkGenThreadingConfig.cooldownSeconds * 1000L;
            } catch (ExecutionException e) {
                System.err.println("[ChunkGenThreading] Worker ERROR for [" + x + "," + z + "]: " + e.getCause());
                futures.remove(key);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.remove(key);
            }
        }

        // Fallback: generate synchronously on main thread
        mainThreadGens.incrementAndGet();
        return generator.generateChunk(x, z);
    }

    /**
     * Submit generation tasks for all unloaded chunks in the look-ahead area.
     * Uses computeIfAbsent to ensure each chunk is only submitted once.
     */
    private static void submitBatch(IChunkGenerator generator,
                                    ChunkProviderServer provider,
                                    int centerX, int centerZ) {
        int r = ChunkGenThreadingConfig.lookAheadRadius;
        int maxFutures = ChunkGenThreadingConfig.maxQueuedChunks;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (futures.size() >= maxFutures) return;

                int cx = centerX + dx;
                int cz = centerZ + dz;
                long k = key(cx, cz);

                // Skip if already handled
                if (futures.containsKey(k)) continue;
                // Skip if already loaded by vanilla
                if (provider.getLoadedChunk(cx, cz) != null) continue;

                // Atomically create a future and submit task
                futures.computeIfAbsent(k, ignored -> {
                    CompletableFuture<Chunk> f = new CompletableFuture<>();
                    final int fx = cx, fz = cz;

                    pool.execute(() -> {
                        IS_WORKER.set(true);
                        try {
                            Chunk chunk = generator.generateChunk(fx, fz);
                            f.complete(chunk);
                        } catch (Throwable t) {
                            f.completeExceptionally(t);
                            System.err.println("[ChunkGenThreading] Worker failed [" + fx + "," + fz + "]: " + t);
                        } finally {
                            IS_WORKER.set(false);
                        }
                    });

                    return f;
                });
            }
        }
    }

    private static void logStats() {
        long now = System.currentTimeMillis();
        if (now - lastStatLog < 5000) return;
        lastStatLog = now;

        long hits = parallelHits.get();
        long main = mainThreadGens.get();
        long total = hits + main;
        int hitPct = total > 0 ? (int) (hits * 100 / total) : 0;

        System.out.println("[ChunkGenThreading] Parallel hits: " + hits +
                " | Main-thread fallback: " + main +
                " | Hit rate: " + hitPct + "%" +
                " | In-flight futures: " + futures.size() +
                " | Workers: " + poolSize);
    }

    public static void reinitialize() {
        createPool();
        futures.clear();
    }

    public static void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        futures.clear();
    }

    static long key(int x, int z) {
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }
}
