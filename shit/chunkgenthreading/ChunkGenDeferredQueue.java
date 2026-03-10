package ded.chunkgenthreading;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Lock-free deferred action queue for chunk generation.
 * Population steps and post-gen mutations are queued here
 * and replayed on the main thread after base terrain is generated.
 */
public final class ChunkGenDeferredQueue {

    private static final ConcurrentLinkedQueue<Runnable> QUEUE = new ConcurrentLinkedQueue<>();

    public static void enqueue(Runnable action) {
        QUEUE.add(action);
    }

    /**
     * Replay all queued actions on the main thread.
     * @return number of actions replayed
     */
    public static int replayAll() {
        int count = 0;
        Runnable action;
        while ((action = QUEUE.poll()) != null) {
            try {
                action.run();
                count++;
            } catch (Exception e) {
                System.err.println("[ChunkGenThreading] Deferred action error: " + e.getMessage());
            }
        }
        return count;
    }

    public static void clear() {
        QUEUE.clear();
    }

    public static int size() {
        return QUEUE.size();
    }
}
