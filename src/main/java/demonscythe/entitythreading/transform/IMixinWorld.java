package demonscythe.entitythreading.transform;

import net.minecraft.entity.Entity;

public interface IMixinWorld {
    void entitythreading$updateChunkPos(Entity entity);
}
