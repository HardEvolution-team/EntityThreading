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

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.spongepowered.asm.mixin.Mixins;

import javax.annotation.Nullable;
import java.util.Map;

public class ThreaderLoadingPlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        System.out.println("[EntityThreading] ASM transformer class requested");
        return new String[]{"demonscythe.entitythreading.transform.WorldClassTransformer"};
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Nullable
    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        System.out.println("[EntityThreading] Registering mixin config via Mixins.addConfiguration()...");
        Mixins.addConfiguration("mixins.entity_threader.json");
        System.out.println("[EntityThreading] Mixin config registered!");
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
