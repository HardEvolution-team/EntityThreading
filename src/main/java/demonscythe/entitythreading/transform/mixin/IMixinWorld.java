package demonscythe.entitythreading.transform.mixin;

import net.minecraft.entity.Entity;

public interface IMixinWorld {
    void entitythreading$updateChunkPos(Entity entity);
}
