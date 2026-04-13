package ded.entitythreading.schedule;

import java.util.ArrayList;

/**
 * Custom thread class for entity worker threads.
 * Carries per-thread state to avoid ThreadLocal lookups in hot paths.
 */
public final class EntityWorkerThread extends Thread {

    volatile boolean isWorker = false;
    volatile boolean isRemote = false;
    long lastChunkKey = Long.MIN_VALUE;
    Object lastChunk = null;
    final ArrayList<Runnable> deferredBuffer = new ArrayList<>(128);

    public EntityWorkerThread(Runnable target, String name) {
        super(target, name);
        setDaemon(true);
        setPriority(Thread.NORM_PRIORITY - 1);
    }

    public static boolean isCurrentThreadWorker() {
        return Thread.currentThread() instanceof EntityWorkerThread ewt && ewt.isWorker;
    }

    public static boolean isCurrentThreadRemote() {
        return Thread.currentThread() instanceof EntityWorkerThread ewt && ewt.isRemote;
    }

    void resetForTask(boolean remote) {
        this.isWorker = true;
        this.isRemote = remote;
        this.lastChunkKey = Long.MIN_VALUE;
        this.lastChunk = null;
        this.deferredBuffer.clear();
    }

    void finishTask() {
        this.isWorker = false;
        this.lastChunk = null;
    }
}
