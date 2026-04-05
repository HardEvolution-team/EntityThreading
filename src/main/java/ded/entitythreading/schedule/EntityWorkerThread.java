package ded.entitythreading.schedule;

import java.util.ArrayList;

public final class EntityWorkerThread extends Thread {

    boolean isWorker = false;
    boolean isRemote = false;
    long lastChunkKey = Long.MIN_VALUE;
    Object lastChunk = null;
    ArrayList<Runnable> deferredBuffer = new ArrayList<>(128);

    public EntityWorkerThread(Runnable target, String name) {
        super(target, name);
        setDaemon(true);
        setPriority(Thread.NORM_PRIORITY - 1);
    }

    public static boolean isCurrentThreadWorker() {
        return Thread.currentThread() instanceof EntityWorkerThread
                && ((EntityWorkerThread) Thread.currentThread()).isWorker;
    }

    public static boolean isCurrentThreadRemote() {
        Thread t = Thread.currentThread();
        if (t instanceof EntityWorkerThread) {
            return ((EntityWorkerThread) t).isRemote;
        }
        return false;
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
