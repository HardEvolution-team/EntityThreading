package ded.otherthreading.schedule;

import net.minecraft.world.World;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Global deferred action queue for True Parallelism.
 */
public final class OtherDeferredActionQueue {

    private static final ConcurrentLinkedQueue<Runnable> GLOBAL_ACTIONS = new ConcurrentLinkedQueue<>();

    public static void enqueue(Runnable action) {
        GLOBAL_ACTIONS.add(action);
    }

    public static int replayAll(World world) {
        int count = 0;
        Runnable action;
        while ((action = GLOBAL_ACTIONS.poll()) != null) {
            try {
                action.run();
                count++;
            } catch (Exception e) {}
        }
        return count;
    }

    public static void clear() {
        GLOBAL_ACTIONS.clear();
    }
}
