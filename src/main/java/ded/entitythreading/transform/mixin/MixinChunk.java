package ded.entitythreading.transform.mixin;

import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;

/**
 * MixinChunk is no longer needed for synchronizing entity collections.
 * ClassInheritanceMultiMap now inherently uses CopyOnWriteArrayList, 
 * making chunk iteration lock-free and preventing ConcurrentModificationException.
 */
@Mixin(Chunk.class)
public abstract class MixinChunk {
    // Empty class, removed synchronized blocks to prevent TPS drops.
}
