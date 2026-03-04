

package ded.entitythreading.schedule;

/**
 * Runnable wrapper for entity group ticking.
 * Used as a task submitted to the ForkJoinPool.
 * Simplified from the original Callable<Boolean> since we no longer need return values;
 * synchronization is handled via CountDownLatch in EntityTickScheduler.
 */
public class GroupTickRunnable implements Runnable {
    private final EntityGroup group;

    public GroupTickRunnable(EntityGroup group) {
        this.group = group;
    }

    @Override
    public void run() {
        try {
            group.runTick();
        } catch (Exception e) {
            System.err.println("[EntityThreading] Exception in GroupTickRunnable: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
