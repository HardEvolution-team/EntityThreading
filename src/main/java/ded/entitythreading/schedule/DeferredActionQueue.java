package ded.entitythreading.schedule;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Lock-free deferred action queue for thread-unsafe world mutations.
 * Separated by Client vs Server to prevent cross-side interference.
 *
 * All debug/stats overhead has been removed — this is a hot path.
 */
public final class DeferredActionQueue {

    private static final ConcurrentLinkedQueue<Runnable> CLIENT_QUEUE = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Runnable> SERVER_QUEUE = new ConcurrentLinkedQueue<>();

    public static void enqueue(Runnable action) {
        enqueue(EntityTickScheduler.isCurrentThreadRemote(), action);
    }

    public static void enqueue(boolean isRemote, Runnable action) {
        if (isRemote) {
            CLIENT_QUEUE.add(action);
        } else {
            SERVER_QUEUE.add(action);
        }
    }

    /**
     * Replay all queued actions for the given side. Must be called from the main thread.
     * @return number of actions replayed
     */
    public static int replayAll(boolean isRemote) {
        int count = 0;
        Runnable action;
        ConcurrentLinkedQueue<Runnable> queue = isRemote ? CLIENT_QUEUE : SERVER_QUEUE;
        while ((action = queue.poll()) != null) {
            try {
                action.run();
                count++;
            } catch (Exception e) {
                System.err.println("[EntityThreading] Deferred action error: " + e.getMessage());
            }
        }
        return count;
    }

    public static void clear() {
        CLIENT_QUEUE.clear();
        SERVER_QUEUE.clear();
    }
}
