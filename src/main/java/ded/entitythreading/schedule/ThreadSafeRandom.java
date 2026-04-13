package ded.entitythreading.schedule;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A {@link Random} subclass that delegates to {@link ThreadLocalRandom}
 * when called from entity worker threads, avoiding contention on the
 * shared {@code World.rand} instance.
 * <p>
 * Thread safety notes:
 * - {@link Random#next(int)} internally uses AtomicLong CAS — thread-safe but contended
 * - {@link Random#nextGaussian()} uses unsynchronized instance fields — NOT thread-safe
 * - We synchronize nextGaussian for the main-thread path to prevent corruption
 *   when the main thread participates in work-stealing
 */
public final class ThreadSafeRandom extends Random {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public ThreadSafeRandom() {
        super();
    }

    public ThreadSafeRandom(long seed) {
        super(seed);
    }

    @Override
    protected int next(int bits) {
        if (Thread.currentThread() instanceof EntityWorkerThread) {
            return ThreadLocalRandom.current().nextInt() >>> (32 - bits);
        }
        // Random.next() uses AtomicLong internally — safe for single-thread access
        return super.next(bits);
    }

    /**
     * nextGaussian() in java.util.Random uses two non-volatile instance fields
     * (haveNextNextGaussian, nextNextGaussian) without synchronization.
     * Since the main thread may call this while workers also call next(),
     * we synchronize the main-thread path.
     */
    @Override
    public synchronized double nextGaussian() {
        if (Thread.currentThread() instanceof EntityWorkerThread) {
            return ThreadLocalRandom.current().nextGaussian();
        }
        return super.nextGaussian();
    }

    // Override commonly used methods to avoid virtual dispatch through next()
    @Override
    public int nextInt() {
        if (Thread.currentThread() instanceof EntityWorkerThread) {
            return ThreadLocalRandom.current().nextInt();
        }
        return super.nextInt();
    }

    @Override
    public int nextInt(int bound) {
        if (Thread.currentThread() instanceof EntityWorkerThread) {
            return ThreadLocalRandom.current().nextInt(bound);
        }
        return super.nextInt(bound);
    }

    @Override
    public float nextFloat() {
        if (Thread.currentThread() instanceof EntityWorkerThread) {
            return ThreadLocalRandom.current().nextFloat();
        }
        return super.nextFloat();
    }

    @Override
    public double nextDouble() {
        if (Thread.currentThread() instanceof EntityWorkerThread) {
            return ThreadLocalRandom.current().nextDouble();
        }
        return super.nextDouble();
    }

    @Override
    public boolean nextBoolean() {
        if (Thread.currentThread() instanceof EntityWorkerThread) {
            return ThreadLocalRandom.current().nextBoolean();
        }
        return super.nextBoolean();
    }

    @Override
    public long nextLong() {
        if (Thread.currentThread() instanceof EntityWorkerThread) {
            return ThreadLocalRandom.current().nextLong();
        }
        return super.nextLong();
    }
}
