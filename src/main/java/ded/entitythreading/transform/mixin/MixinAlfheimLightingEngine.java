package ded.entitythreading.transform.mixin;

import ded.entitythreading.schedule.EntityTickScheduler;
import dev.redstudio.alfheim.lighting.LightingEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Alfheim has a hardcoded thread check in its LightingEngine.lock() method
 * that throws an IllegalAccessException if the current thread is not the main Server thread.
 * 
 * Since our worker threads need to query lighting (for entity spawning, AI, burning, etc.),
 * we must bypass this exception.
 */
@Mixin(value = LightingEngine.class, remap = false)
public class MixinAlfheimLightingEngine {

    @Inject(method = "processLightUpdatesForType", at = @At("HEAD"), cancellable = true, remap = false)
    private void onProcessLightUpdatesForType(net.minecraft.world.EnumSkyBlock type, CallbackInfo ci) {
        // Light updates involve state queues that cannot be processed concurrently by multiple worker threads.
        // If a worker thread asks for light, we simply cancel the queue processing and let Chunk.getLightFor 
        // fall back to the currently cached array value.
        if (EntityTickScheduler.isEntityThread()) {
            ci.cancel();
        }
    }
}
