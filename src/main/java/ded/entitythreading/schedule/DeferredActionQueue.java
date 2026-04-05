package ded.entitythreading.schedule;

import ded.entitythreading.EntityThreadingMod;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class DeferredActionQueue {

    private static final ConcurrentLinkedQueue<Runnable> GLOBAL_SERVER = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Runnable> GLOBAL_CLIENT = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<ArrayList<Runnable>> WORKER_BUFFERS = new ConcurrentLinkedQueue<>();

    public static void enqueue(Runnable action) {
        Thread t = Thread.currentThread();
        if (t instanceof EntityWorkerThread) {
            ((EntityWorkerThread) t).deferredBuffer.add(action);
        } else {
            enqueueGlobal(EntityTickScheduler.isCurrentThreadRemote(), action);
        }
    }

    public static void enqueue(boolean isRemote, Runnable action) {
        Thread t = Thread.currentThread();
        if (t instanceof EntityWorkerThread) {
            ((EntityWorkerThread) t).deferredBuffer.add(action);
        } else {
            enqueueGlobal(isRemote, action);
        }
    }

    private static void enqueueGlobal(boolean isRemote, Runnable action) {
        (isRemote ? GLOBAL_CLIENT : GLOBAL_SERVER).add(action);
    }

    static void submitWorkerBuffer(ArrayList<Runnable> buffer) {
        if (!buffer.isEmpty()) {
            WORKER_BUFFERS.add(buffer);
        }
    }

    public static int replayAll(boolean isRemote) {
        int count = 0;

        ArrayList<Runnable> workerBuf;
        while ((workerBuf = WORKER_BUFFERS.poll()) != null) {
            for (Runnable runnable : workerBuf) {
                try {
                    runnable.run();
                    count++;
                } catch (Exception e) {
                    EntityThreadingMod.LOGGER.error("Deferred action error: {}", e.getMessage());
                }
            }
        }

        Runnable action;
        ConcurrentLinkedQueue<Runnable> queue = isRemote ? GLOBAL_CLIENT : GLOBAL_SERVER;
        while ((action = queue.poll()) != null) {
            try {
                action.run();
                count++;
            } catch (Exception e) {
                EntityThreadingMod.LOGGER.error("Deferred action error: {}", e.getMessage());
            }
        }

        return count;
    }

    public static void clear() {
        GLOBAL_CLIENT.clear();
        GLOBAL_SERVER.clear();
        WORKER_BUFFERS.clear();
    }
}
