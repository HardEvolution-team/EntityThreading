package ded.chunkgenthreading.mixin;

import ded.chunkgenthreading.ParallelChunkGenScheduler;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minimal World guard for chunk gen worker threads.
 *
 * The ONLY thing workers need protection from is calling into ChunkProviderServer
 * to get neighboring chunks — which would cause recursive chunk gen or
 * ConcurrentModificationException on the id2ChunkMap.
 *
 * Note: generateChunk() does NOT call setBlockState, spawnEntity, etc.
 * Those happen only in populate(), which always runs on the main thread.
 * Therefore, we do NOT intercept write operations — they simply don't occur
 * during generateChunk() in any properly written generator.
 *
 * If a modded generator calls getChunk() to read a neighbor (e.g. for biome
 * blending) we return EmptyChunk to prevent recursion / lock contention.
 */
@Mixin(value = World.class, priority = 998)
public abstract class MixinWorldChunkGen {

    /**
     * Redirect getChunk() on worker threads to EmptyChunk.
     * Prevents recursive ChunkProviderServer access which is NOT thread-safe.
     */
    @Inject(
            method = "getChunk(II)Lnet/minecraft/world/chunk/Chunk;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chunkGen$getChunk(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        if (ParallelChunkGenScheduler.isWorkerThread()) {
            cir.setReturnValue(new EmptyChunk((World) (Object) this, x, z));
        }
    }

    /**
     * Redirect isChunkLoaded() on worker threads to false.
     * Prevents touching ChunkProviderServer's chunk map from worker threads.
     */
    @Inject(
            method = "isChunkLoaded(IIZ)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chunkGen$isChunkLoaded(int x, int z, boolean allowEmpty,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (ParallelChunkGenScheduler.isWorkerThread()) {
            cir.setReturnValue(false);
        }
    }
}
