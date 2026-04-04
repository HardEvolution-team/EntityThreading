package ded.entitythreading.transform.mixin;

import ded.entitythreading.EntityThreadingConfig;
import ded.entitythreading.schedule.AsyncPathProcessor;
import net.minecraft.entity.EntityLiving;
import net.minecraft.pathfinding.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PathNavigate.class)
public abstract class MixinPathNavigate {

    @Shadow protected EntityLiving entity;
    @Shadow protected World world;
    @Shadow protected abstract boolean canNavigate();
    @Shadow protected abstract float getPathSearchRange();
    @Shadow protected abstract PathFinder getPathFinder();

    @Inject(method = "getPathToPos", at = @At("HEAD"), cancellable = true)
    private void onGetPathToPos(BlockPos pos, CallbackInfoReturnable<Path> cir) {
        if (!EntityThreadingConfig.enabled || !EntityThreadingConfig.asyncPathfinding) return;
        int entityId = this.entity.getEntityId();
        Path completed = AsyncPathProcessor.pollCompleted(entityId);
        if (completed != null) { cir.setReturnValue(completed); return; }
        if (AsyncPathProcessor.isPending(entityId)) { cir.setReturnValue(null); return; }
        if (!this.canNavigate()) { cir.setReturnValue(null); return; }
        submitAsyncPath(entityId, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, getPathSearchRange());
        cir.setReturnValue(null);
    }

    @Inject(method = "getPathToEntityLiving", at = @At("HEAD"), cancellable = true)
    private void onGetPathToEntityLiving(net.minecraft.entity.Entity targetEntity, CallbackInfoReturnable<Path> cir) {
        if (!EntityThreadingConfig.enabled || !EntityThreadingConfig.asyncPathfinding) return;
        int entityId = this.entity.getEntityId();
        Path completed = AsyncPathProcessor.pollCompleted(entityId);
        if (completed != null) { cir.setReturnValue(completed); return; }
        if (AsyncPathProcessor.isPending(entityId)) { cir.setReturnValue(null); return; }
        if (!this.canNavigate()) { cir.setReturnValue(null); return; }
        submitAsyncPath(entityId, targetEntity.posX, targetEntity.getEntityBoundingBox().minY, targetEntity.posZ, getPathSearchRange());
        cir.setReturnValue(null);
    }

    private void submitAsyncPath(int entityId, double x, double y, double z, float range) {
        try {
            BlockPos entityPos = new BlockPos(this.entity);
            int margin = (int) (range + 8.0F);
            ChunkCache chunkCache = new ChunkCache(
                    this.world,
                    entityPos.add(-margin, -margin, -margin),
                    entityPos.add(margin, margin, margin), 0);
            PathFinder asyncFinder = this.getPathFinder();
            AsyncPathProcessor.submitPathRequest(entityId, asyncFinder, chunkCache, this.entity, x, y, z, range);
        } catch (Exception ignored) {}
    }
}
