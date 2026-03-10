package ded.chunkgenthreading.mixin;

import ded.chunkgenthreading.ChunkGenThreadingConfig;
import ded.chunkgenthreading.ParallelChunkGenScheduler;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.IChunkGenerator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Core hook: redirects IChunkGenerator.generateChunk(x,z) call inside
 * ChunkProviderServer.provideChunk(x, z) to our parallel scheduler.
 *
 * This is the ONLY change to vanilla flow:
 *   Before: main thread → generateChunk(x,z) → return chunk
 *   After:  main thread → submitBatch(surrounding area) → future.get(x,z)
 *           workers    → generateChunk(n,m) for each surrounding chunk in parallel
 *
 * ALL vanilla post-gen logic is preserved:
 *   - id2ChunkMap.put(key, chunk)
 *   - chunk.onLoad()
 *   - populate() (decoration, structures, vanilla entities)
 *   - Forge ChunkEvent.Load
 *   - Light engine updates
 */
@Mixin(ChunkProviderServer.class)
public abstract class MixinChunkProviderServer {

    @Shadow @Final public IChunkGenerator chunkGenerator;
    @Shadow @Final public WorldServer world;

    @Redirect(
            method = "provideChunk",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/gen/IChunkGenerator;generateChunk(II)Lnet/minecraft/world/chunk/Chunk;")
    )
    private Chunk redirectGenerateChunk(IChunkGenerator generator, int x, int z) {
        if (world.isRemote) {
            return generator.generateChunk(x, z);
        }
        ChunkProviderServer provider = (ChunkProviderServer) (Object) this;
        return ParallelChunkGenScheduler.generateOrGet(generator, provider, x, z);
    }
}
