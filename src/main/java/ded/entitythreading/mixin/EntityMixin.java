package ded.entitythreading.mixin;

import ded.entitythreading.interfaces.IEntityActivation;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Entity.class)
public class EntityMixin implements IEntityActivation {

    @Unique
    private boolean entitythreading$active = true;

    @Override
    public boolean entitythreading$isActive() {
        return entitythreading$active;
    }

    @Override
    public void entitythreading$setActive(boolean active) {
        this.entitythreading$active = active;
    }
}
