package ded.entitythreading.schedule;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A {@link Random} subclass that delegates to {@link ThreadLocalRandom}
 * when called from a virtual entity-tick thread, avoiding contention on the
 * shared {@code World.rand} instance.
 * <p>
 * Thread-safety notes:
 * <ul>
 *   <li>{@link Random#next(int)} uses {@code AtomicLong} CAS internally — thread-safe but contended.</li>
 *   <li>{@link Random#nextGaussian()} uses unsynchronized instance fields — NOT thread-safe.</li>
 *   <li>We synchronize {@code nextGaussian} for the main-thread path to prevent corruption
 *       when the main thread participates as a work-stealer.</li>
 * </ul>
 * <p>
 * Detection of "entity thread" is done via {@link EntityTickScheduler#TICK_CONTEXT}
 * ({@link ScopedValue}) instead of the old {@code instanceof EntityWorkerThread} check,
 * so this class works correctly with Java 25 virtual threads.
 */
public final class ThreadSafeRandom extends Random {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public ThreadSafeRandom() { super(); }
    public ThreadSafeRandom(long seed) { super(seed); }

    /** Returns {@code true} when called from a virtual entity-tick thread. */
    private static boolean isEntityThread() {
        return EntityTickScheduler.TICK_CONTEXT.isBound();
    }

    @Override
    protected int next(int bits) {
        if (isEntityThread()) {
            return ThreadLocalRandom.current().nextInt() >>> (32 - bits);
        }
        return super.next(bits);
    }

    /**
     * {@code nextGaussian()} in {@link java.util.Random} uses two non-volatile instance fields
     * without synchronization. We synchronize the main-thread path to prevent data corruption.
     */
    @Override
    public synchronized double nextGaussian() {
        if (isEntityThread()) {
            return ThreadLocalRandom.current().nextGaussian();
        }
        return super.nextGaussian();
    }

    @Override
    public int nextInt() {
        return isEntityThread() ? ThreadLocalRandom.current().nextInt() : super.nextInt();
    }

    @Override
    public int nextInt(int bound) {
        return isEntityThread() ? ThreadLocalRandom.current().nextInt(bound) : super.nextInt(bound);
    }

    @Override
    public float nextFloat() {
        return isEntityThread() ? ThreadLocalRandom.current().nextFloat() : super.nextFloat();
    }

    @Override
    public double nextDouble() {
        return isEntityThread() ? ThreadLocalRandom.current().nextDouble() : super.nextDouble();
    }

    @Override
    public boolean nextBoolean() {
        return isEntityThread() ? ThreadLocalRandom.current().nextBoolean() : super.nextBoolean();
    }

    @Override
    public long nextLong() {
        return isEntityThread() ? ThreadLocalRandom.current().nextLong() : super.nextLong();
    }
}
