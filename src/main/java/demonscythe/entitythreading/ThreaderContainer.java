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

package demonscythe.entitythreading;

import com.google.common.eventbus.EventBus;
import net.minecraftforge.fml.common.DummyModContainer;
import net.minecraftforge.fml.common.LoadController;
import net.minecraftforge.fml.common.ModMetadata;

public class ThreaderContainer extends DummyModContainer {
    public ThreaderContainer() {
        super(new ModMetadata());
        ModMetadata metadata = getMetadata();
        metadata.modId = "entity_threader";
        metadata.name = "Mob_Entity_Threader";
        metadata.description = "Adds hooks and scheduling to thread groups of mob entities";
        metadata.version = "0.1";
    }

    @Override
    public boolean registerBus(EventBus bus, LoadController controller) {
        bus.register(this);
        return true;
    }
}
