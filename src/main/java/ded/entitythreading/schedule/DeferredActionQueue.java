

package ded.entitythreading.schedule;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;


public final class DeferredActionQueue {

    private static final ConcurrentLinkedQueue<Runnable> GLOBAL_QUEUE = new ConcurrentLinkedQueue<>();

    /**
     * Enqueue an action to be executed on the main thread after tick completes.
     * Safe to call from any worker thread.
     */
    public static void enqueue(Runnable action) {
        GLOBAL_QUEUE.add(action);
    }

    /**
     * Drain all queued actions and execute them on the calling (main) thread.
     * Returns the number of actions replayed.
     */
    public static int replayAll() {
        int count = 0;
        // Drain into a local list first to avoid infinite loops if actions enqueue more actions
        List<Runnable> batch = new ArrayList<>();
        Runnable action;
        while ((action = GLOBAL_QUEUE.poll()) != null) {
            batch.add(action);
        }
        for (Runnable r : batch) {
            try {
                r.run();
            } catch (Exception e) {
                System.err.println("[EntityThreading] Error replaying deferred action: " + e.getMessage());
                e.printStackTrace();
            }
            count++;
        }
        return count;
    }

    /**
     * Clear any pending actions without executing them (for error recovery).
     */
    public static void clear() {
        GLOBAL_QUEUE.clear();
    }

    /**
     * Check if there are pending deferred actions.
     */
    public static boolean hasPending() {
        return !GLOBAL_QUEUE.isEmpty();
    }
}
