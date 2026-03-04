package ded.entitythreading.schedule;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;


public class EntityGroup {
    // Swap-buffer: main thread writes to pending, worker reads from ticking
    private List<Entity> pendingEntities = new ArrayList<>();
    private List<Entity> tickingEntities = new ArrayList<>();

    // Idle tick counter for eviction (atomic for safe cross-thread reads)
    private final AtomicInteger idleTicks = new AtomicInteger(0);

    public EntityGroup(World world) {
    }

    /**
     * Add an entity to be ticked. Called from main thread only.
     */
    public synchronized void addEntity(Entity entity) {
        pendingEntities.add(entity);
    }

    /**
     * Check if there are entities queued for ticking.
     */
    public synchronized boolean hasEntities() {
        return !pendingEntities.isEmpty();
    }

    /**
     * Get the count of pending entities.
     */
    public synchronized int getEntityCount() {
        return pendingEntities.size();
    }

    /**
     * Run the tick for all queued entities. Called from worker thread.
     *
     * Uses swap-buffer pattern: swap pending into ticking, then process ticking.
     * The main thread won't touch pendingEntities again until after
     * waitForFinish(),
     * so this is safe.
     */
    public void runTick() {
        // Swap buffers atomically
        List<Entity> toTick;
        synchronized (this) {
            toTick = tickingEntities;
            tickingEntities = pendingEntities;
            // Capture a NEW list for pending to ensure worker owns tickingEntities entirely
            pendingEntities = new ArrayList<>(toTick.size() > 0 ? toTick.size() : 16);
        }

        // Tick each entity with individual error handling
        // tickingEntities is now uniquely owned by this thread until this method ends
        for (int i = 0, size = tickingEntities.size(); i < size; i++) {
            Entity entity = tickingEntities.get(i);
            EntityTickScheduler.safeTick(entity.world, entity);
        }

        tickingEntities.clear();
    }

    /**
     * Reset idle tick counter (called when group has entities).
     */
    public void resetIdleTicks() {
        idleTicks.set(0);
    }

    /**
     * Increment idle ticks and check if group should be evicted.
     * Returns true if the group has exceeded the eviction threshold.
     */
    public boolean incrementIdleAndCheck(int maxIdleTicks) {
        return idleTicks.incrementAndGet() > maxIdleTicks;
    }
}
