package ded.entitythreading.schedule;

import ded.entitythreading.EntityThreadingMod;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Collects actions deferred from worker threads and replays them on the main thread.
 * <p>
 * Worker threads accumulate actions in thread-local buffers (via {@link EntityWorkerThread})
 * which are bulk-submitted when the worker finishes its batch. This minimizes contention
 * on the global queues.
 * <p>
 * All queues are separated by side (client/server) to prevent cross-contamination
 * on integrated servers.
 */
public final class DeferredActionQueue {

    private static final ConcurrentLinkedQueue<Runnable> GLOBAL_SERVER = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Runnable> GLOBAL_CLIENT = new ConcurrentLinkedQueue<>();

    // Worker buffers are now separated by side
    private static final ConcurrentLinkedQueue<ArrayList<Runnable>> WORKER_BUFFERS_SERVER = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<ArrayList<Runnable>> WORKER_BUFFERS_CLIENT = new ConcurrentLinkedQueue<>();

    private DeferredActionQueue() {}

    public static void enqueue(Runnable action) {
        Thread t = Thread.currentThread();
        if (t instanceof EntityWorkerThread worker) {
            worker.deferredBuffer.add(action);
        } else {
            enqueueGlobal(EntityTickScheduler.isCurrentThreadRemote(), action);
        }
    }

    public static void enqueue(boolean isRemote, Runnable action) {
        Thread t = Thread.currentThread();
        if (t instanceof EntityWorkerThread worker) {
            worker.deferredBuffer.add(action);
        } else {
            enqueueGlobal(isRemote, action);
        }
    }

    private static void enqueueGlobal(boolean isRemote, Runnable action) {
        (isRemote ? GLOBAL_CLIENT : GLOBAL_SERVER).add(action);
    }

    /**
     * Called by worker threads when they finish a batch.
     * The isRemote flag determines which side's queue receives the buffer.
     */
    static void submitWorkerBuffer(ArrayList<Runnable> buffer, boolean isRemote) {
        if (!buffer.isEmpty()) {
            (isRemote ? WORKER_BUFFERS_CLIENT : WORKER_BUFFERS_SERVER).add(buffer);
        }
    }

    /**
     * Replays all deferred actions on the calling (main) thread.
     *
     * @param isRemote whether to replay client-side or server-side actions
     * @return the number of actions replayed
     */
    public static int replayAll(boolean isRemote) {
        int count = 0;

        // Drain worker buffers for the correct side
        ConcurrentLinkedQueue<ArrayList<Runnable>> workerQueue =
                isRemote ? WORKER_BUFFERS_CLIENT : WORKER_BUFFERS_SERVER;

        ArrayList<Runnable> workerBuf;
        while ((workerBuf = workerQueue.poll()) != null) {
            for (int i = 0, size = workerBuf.size(); i < size; i++) {
                count += executeAction(workerBuf.get(i));
            }
        }

        // Drain global queue for the correct side
        ConcurrentLinkedQueue<Runnable> queue = isRemote ? GLOBAL_CLIENT : GLOBAL_SERVER;
        Runnable action;
        while ((action = queue.poll()) != null) {
            count += executeAction(action);
        }

        return count;
    }

    private static int executeAction(Runnable action) {
        try {
            action.run();
            return 1;
        } catch (Exception e) {
            EntityThreadingMod.LOGGER.error("Deferred action error: {}", e.getMessage(), e);
            return 0;
        }
    }

    public static void clear() {
        GLOBAL_CLIENT.clear();
        GLOBAL_SERVER.clear();
        WORKER_BUFFERS_CLIENT.clear();
        WORKER_BUFFERS_SERVER.clear();
    }
}
