package ded.entitythreading.mixin;

import ded.entitythreading.EntityThreadingMod;
import ded.entitythreading.config.EntityThreadingConfig;
import ded.entitythreading.schedule.AsyncPathProcessor;
import net.minecraft.entity.EntityLiving;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathFinder;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PathNavigate.class)
public abstract class PathNavigateMixin {

    @Shadow protected EntityLiving entity;
    @Shadow protected World world;

    @Shadow protected abstract boolean canNavigate();
    @Shadow public abstract float getPathSearchRange();
    @Shadow protected abstract PathFinder getPathFinder();

    /**
     * Cached PathFinder instance to avoid re-creation every call.
     * getPathFinder() in vanilla creates a new PathFinder each time.
     */
    @Unique
    private PathFinder entitythreading$cachedPathFinder;

    @Inject(method = "getPathToPos", at = @At("HEAD"), cancellable = true)
    private void onGetPathToPos(BlockPos pos, CallbackInfoReturnable<Path> cir) {
        if (!EntityThreadingConfig.enabled || !EntityThreadingConfig.asyncPathfinding) {
            return;
        }
        // Never async-pathfind on client side
        if (this.world.isRemote) {
            return;
        }

        int entityId = this.entity.getEntityId();

        Path completed = AsyncPathProcessor.pollCompleted(entityId);
        if (completed != null) {
            cir.setReturnValue(completed);
            return;
        }

        if (AsyncPathProcessor.isPending(entityId)) {
            cir.setReturnValue(null);
            return;
        }

        if (!this.canNavigate()) {
            cir.setReturnValue(null);
            return;
        }

        entitythreading$submitAsyncPath(entityId, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        cir.setReturnValue(null);
    }

    @Inject(method = "getPathToEntityLiving", at = @At("HEAD"), cancellable = true)
    private void onGetPathToEntityLiving(net.minecraft.entity.Entity entityIn,
                                         CallbackInfoReturnable<Path> cir) {
        if (!EntityThreadingConfig.enabled || !EntityThreadingConfig.asyncPathfinding) {
            return;
        }
        if (this.world.isRemote) {
            return;
        }

        int entityId = this.entity.getEntityId();

        Path completed = AsyncPathProcessor.pollCompleted(entityId);
        if (completed != null) {
            cir.setReturnValue(completed);
            return;
        }

        if (AsyncPathProcessor.isPending(entityId)) {
            cir.setReturnValue(null);
            return;
        }

        if (!this.canNavigate()) {
            cir.setReturnValue(null);
            return;
        }

        entitythreading$submitAsyncPath(entityId,
                entityIn.posX, entityIn.getEntityBoundingBox().minY, entityIn.posZ);
        cir.setReturnValue(null);
    }

    /**
     * Submits an async pathfinding request.
     *
     * IMPORTANT: ChunkCache MUST be created on the main thread (or at least on the
     * thread that owns the world) because its constructor reads chunk data.
     * The PathFinder is cached to avoid per-call allocation.
     */
    @Unique
    private void entitythreading$submitAsyncPath(int entityId, double x, double y, double z) {
        float range = getPathSearchRange();
        try {
            BlockPos entityPos = new BlockPos(this.entity);
            int margin = (int) (range + 8.0F);

            // ChunkCache created on calling thread (main thread) — this is the world snapshot
            ChunkCache chunkCache = new ChunkCache(
                    this.world,
                    entityPos.add(-margin, -margin, -margin),
                    entityPos.add(margin, margin, margin),
                    0
            );

            // Cache the PathFinder to avoid creating a new one every call
            if (this.entitythreading$cachedPathFinder == null) {
                this.entitythreading$cachedPathFinder = this.getPathFinder();
            }

            AsyncPathProcessor.submitPathRequest(entityId, this.entitythreading$cachedPathFinder,
                    chunkCache, this.entity, x, y, z, range);
        } catch (Exception e) {
            EntityThreadingMod.LOGGER.debug("Failed to submit async path for entity {}: {}",
                    entityId, e.getMessage());
        }
    }
}
