package ded.entitythreading.transform.mixin;

import ded.entitythreading.schedule.DeferredActionQueue;
import ded.entitythreading.schedule.EntityTickScheduler;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.eventhandler.ASMEventHandler;
import net.minecraftforge.fml.common.eventhandler.Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Acts as a thread-safety sandbox for Forge Events.
 * If a thread-unsafe mod (like HBM) attempts to process a Forge event (like LivingUpdateEvent)
 * triggered by a background worker thread, this intercepts the execution and securely defers
 * the mod's specific listener to the main thread.
 */
@Mixin(value = ASMEventHandler.class, remap = false)
public class MixinASMEventHandler {

    @Shadow private ModContainer owner;

    @Inject(method = "invoke", at = @At("HEAD"), cancellable = true)
    private void onInvokeSandboxed(Event event, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread() && this.owner != null) {
            String modId = this.owner.getModId();

            // Check if this mod is known to be thread-unsafe (e.g. HBM NTM)
            if (EntityTickScheduler.isModEventBlacklisted(modId)) {
                // Defer ONLY this specific mod's event listener to the main thread.
                // Other thread-safe mods (and vanilla logic) will continue executing in parallel!
                DeferredActionQueue.enqueue(() -> {
                    try {
                        ((ASMEventHandler)(Object)this).invoke(event);
                    } catch (Throwable t) {
                        // Ignore downstream exceptions from the deferred mod logic
                    }
                });
                ci.cancel(); // Stop execution on the worker thread
            }
        }
    }
}