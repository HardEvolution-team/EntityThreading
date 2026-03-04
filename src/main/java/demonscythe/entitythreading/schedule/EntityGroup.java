/*
 * Copyright (c) 2020  DemonScythe45
 *
 * This file is part of EntityThreading
 *
 *     EntityThreading is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation; version 3 only
 *
 *     EntityThreading is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with EntityThreading.  If not, see <https://www.gnu.org/licenses/>
 */

package demonscythe.entitythreading.schedule;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A group of entities in the same chunk region that are ticked together on one
 * worker thread.
 *
 * Uses a swap-buffer pattern: entities are added to `pendingEntities` from the
 * main thread,
 * then swapped into `tickingEntities` for the worker thread to process. This
 * avoids
 * ConcurrentModificationException without needing locks on the hot path.
 */
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
