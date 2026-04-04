package ded.entitythreading.schedule;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public final class ThreadSafeRandom extends Random {

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
        return super.next(bits);
    }

    @Override
    public double nextGaussian() {
        if (Thread.currentThread() instanceof EntityWorkerThread) {
            return ThreadLocalRandom.current().nextGaussian();
        }
        return super.nextGaussian();
    }
}
