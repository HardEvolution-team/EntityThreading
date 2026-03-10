package ded.chunkgenthreading.mixin;

import ded.chunkgenthreading.ParallelChunkGenScheduler;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minimal WorldServer guard for chunk gen worker threads.
 * Same logic as MixinWorldChunkGen — prevents workers from calling
 * WorldServer-specific chunk access methods.
 */
@Mixin(WorldServer.class)
public abstract class MixinWorldServerChunkGen {

    @Inject(
            method = "getChunkFromChunkCoords",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chunkGen$getChunkFromChunkCoords(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        if (ParallelChunkGenScheduler.isWorkerThread()) {
            cir.setReturnValue(new EmptyChunk((World) (Object) this, x, z));
        }
    }
}
