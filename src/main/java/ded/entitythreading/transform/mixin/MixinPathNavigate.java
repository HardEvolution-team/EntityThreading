package ded.entitythreading.transform.mixin;

import ded.entitythreading.EntityThreadingConfig;
import ded.entitythreading.schedule.AsyncPathProcessor;
import net.minecraft.entity.EntityLiving;
import net.minecraft.pathfinding.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

/**
 * Intercepts PathNavigate path computation to run it asynchronously.
 *
 * How it works:
 * 1. When an entity requests a path, check for a pre-computed async result first.
 * 2. If no result is available, submit the path computation to a worker thread
 *    using a FRESH PathFinder + ChunkCache snapshot, then return null.
 * 3. The entity's AI handles null paths gracefully (it just doesn't move that tick).
 * 4. On the next tick, the result is ready and returned immediately.
 *
 * Safety:
 * - ChunkCache is a snapshot of block data — immutable, safe for concurrent reads.
 * - A NEW PathFinder is created for each async task via getPathFinder().
 * - Main thread is NEVER blocked.
 */
@Mixin(PathNavigate.class)
public abstract class MixinPathNavigate {

    @Shadow
    protected EntityLiving entity;

    @Shadow
    protected World world;

    @Shadow
    protected abstract boolean canNavigate();

    @Shadow
    protected abstract float getPathSearchRange();

    @Shadow
    protected abstract PathFinder getPathFinder();

    /**
     * Intercept getPathToPos to use async pathfinding.
     */
    @Inject(method = "getPathToPos", at = @At("HEAD"), cancellable = true)
    private void onGetPathToPos(BlockPos pos, CallbackInfoReturnable<Path> cir) {
        if (!EntityThreadingConfig.enabled || !EntityThreadingConfig.asyncPathfinding)
            return;

        int entityId = this.entity.getEntityId();

        // 1. Check for completed async result
        Path completed = AsyncPathProcessor.pollCompleted(entityId);
        if (completed != null) {
            cir.setReturnValue(completed);
            return;
        }

        // 2. If computation already in-flight, don't submit another one
        if (AsyncPathProcessor.isPending(entityId)) {
            cir.setReturnValue(null);
            return;
        }

        // 3. Can't navigate? Return null as vanilla would
        if (!this.canNavigate()) {
            cir.setReturnValue(null);
            return;
        }

        // 4. Submit async path computation
        float range = this.getPathSearchRange();
        double targetX = (double) pos.getX() + 0.5D;
        double targetY = (double) pos.getY() + 0.5D;
        double targetZ = (double) pos.getZ() + 0.5D;

        submitAsyncPath(entityId, targetX, targetY, targetZ, range);
        cir.setReturnValue(null);
    }

    /**
     * Intercept getPathToEntityLiving to use async pathfinding.
     */
    @Inject(method = "getPathToEntityLiving", at = @At("HEAD"), cancellable = true)
    private void onGetPathToEntityLiving(net.minecraft.entity.Entity targetEntity,
            CallbackInfoReturnable<Path> cir) {
        if (!EntityThreadingConfig.enabled || !EntityThreadingConfig.asyncPathfinding)
            return;

        int entityId = this.entity.getEntityId();

        // 1. Check for completed async result
        Path completed = AsyncPathProcessor.pollCompleted(entityId);
        if (completed != null) {
            cir.setReturnValue(completed);
            return;
        }

        // 2. If computation in-flight, wait
        if (AsyncPathProcessor.isPending(entityId)) {
            cir.setReturnValue(null);
            return;
        }

        // 3. Can't navigate? Return null
        if (!this.canNavigate()) {
            cir.setReturnValue(null);
            return;
        }

        // 4. Submit async path computation
        float range = this.getPathSearchRange();
        double targetX = targetEntity.posX;
        double targetY = targetEntity.getEntityBoundingBox().minY;
        double targetZ = targetEntity.posZ;

        submitAsyncPath(entityId, targetX, targetY, targetZ, range);
        cir.setReturnValue(null);
    }

    /**
     * Create a fresh ChunkCache snapshot and PathFinder, then submit async computation.
     * getPathFinder() creates a NEW PathFinder instance — no shared mutable state.
     */
    private void submitAsyncPath(int entityId, double x, double y, double z, float range) {
        try {
            // Create ChunkCache snapshot (main thread — safe)
            BlockPos entityPos = new BlockPos(this.entity);
            int margin = (int) (range + 8.0F);
            ChunkCache chunkCache = new ChunkCache(
                    this.world,
                    entityPos.add(-margin, -margin, -margin),
                    entityPos.add(margin, margin, margin),
                    0);

            // Create a FRESH PathFinder for async use
            PathFinder asyncFinder = this.getPathFinder();

            AsyncPathProcessor.submitPathRequest(
                    entityId, asyncFinder, chunkCache,
                    this.entity, x, y, z, range);
        } catch (Exception e) {
            if (EntityThreadingConfig.debugLogging) {
                System.err.println("[EntityThreading] Failed to submit async path: " + e.getMessage());
            }
            // Fallback: let vanilla handle it synchronously
        }
    }
}
