
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
        Runnable action;
        while ((action = GLOBAL_QUEUE.poll()) != null) {
            try {
                action.run();
                count++;
            } catch (Exception e) {
                System.err.println("[EntityThreading] Error replaying deferred action: " + e.getMessage());
                e.printStackTrace();
            }
        }
        return count;
    }

    /**
     * Replay a single action if available.
     * Returns true if an action was replayed.
     */
    public static boolean replayOne() {
        Runnable action = GLOBAL_QUEUE.poll();
        if (action != null) {
            try {
                action.run();
                return true;
            } catch (Exception e) {
                System.err.println("[EntityThreading] Error replaying deferred action: " + e.getMessage());
                e.printStackTrace();
            }
        }
        return false;
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
