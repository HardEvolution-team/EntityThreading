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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe queue that collects world-mutating actions from worker threads.
 * Actions are deferred and replayed on the main server thread after all entity ticks complete.
 * This prevents race conditions on World state (block changes, entity spawns, sounds, etc.).
 *
 * Each worker thread pushes Runnables into the shared queue. After waitForFinish(),
 * the main thread drains and executes them all sequentially.
 */
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
