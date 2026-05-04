package ded.entitythreading.schedule;

import ded.entitythreading.EntityThreadingMod;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Collects actions deferred from virtual entity-tick threads and replays them on the main thread.
 * <p>
 * <b>Old model:</b> Worker threads accumulated actions in fields on {@link EntityWorkerThread}.<br>
 * <b>New model:</b> Each virtual entity-tick thread has an {@link EntityTickContext} bound via
 * {@link EntityTickScheduler#TICK_CONTEXT} (a {@link ScopedValue}).  The context's
 * {@code deferredBuffer} is flushed to {@link #WORKER_BUFFERS_SERVER} / {@link #WORKER_BUFFERS_CLIENT}
 * when the virtual thread's task completes ({@link EntityTickContext#flush()}).
 * <p>
 * All queues are separated by side (client / server) to prevent cross-contamination on
 * integrated servers.
 */
public final class DeferredActionQueue {

    private static final ConcurrentLinkedQueue<Runnable> GLOBAL_SERVER = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Runnable> GLOBAL_CLIENT = new ConcurrentLinkedQueue<>();

    private static final ConcurrentLinkedQueue<ArrayList<Runnable>> WORKER_BUFFERS_SERVER = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<ArrayList<Runnable>> WORKER_BUFFERS_CLIENT = new ConcurrentLinkedQueue<>();

    private DeferredActionQueue() {}

    /**
     * Enqueues an action to be replayed on the main thread.
     * <p>
     * If called from a virtual entity-tick thread ({@code TICK_CONTEXT} is bound), the action
     * is added to the thread-local deferred buffer to minimize contention on the global queue.
     * Otherwise the action is submitted directly to the global queue.
     */
    public static void enqueue(Runnable action) {
        if (EntityTickScheduler.TICK_CONTEXT.isBound()) {
            EntityTickScheduler.TICK_CONTEXT.get().deferredBuffer.add(action);
        } else {
            enqueueGlobal(EntityTickScheduler.isCurrentThreadRemote(), action);
        }
    }

    public static void enqueue(boolean isRemote, Runnable action) {
        if (EntityTickScheduler.TICK_CONTEXT.isBound()) {
            EntityTickScheduler.TICK_CONTEXT.get().deferredBuffer.add(action);
        } else {
            enqueueGlobal(isRemote, action);
        }
    }

    private static void enqueueGlobal(boolean isRemote, Runnable action) {
        (isRemote ? GLOBAL_CLIENT : GLOBAL_SERVER).add(action);
    }

    /**
     * Called by {@link EntityTickContext#flush()} at the end of each virtual-thread task.
     * The {@code isRemote} flag is taken from the context itself.
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

        // Drain per-virtual-thread buffers for the correct side
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
