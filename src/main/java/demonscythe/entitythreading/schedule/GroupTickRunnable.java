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
