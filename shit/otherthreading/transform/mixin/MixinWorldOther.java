package ded.otherthreading.transform.mixin;

import ded.otherthreading.OtherThreadingConfig;
import ded.otherthreading.schedule.BlockTickScheduler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(World.class)
public abstract class MixinWorldOther {

    @Shadow protected boolean processingLoadedTiles;

    /**
     * Intercept TileEntity ticking in updateEntities().
     */
    @Redirect(method = "updateEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ITickable;update()V"))
    private void redirectTileEntityUpdate(ITickable tickable) {
        if (!OtherThreadingConfig.tileEntityThreadingEnabled || !(tickable instanceof TileEntity)) {
            tickable.update();
            return;
        }

        BlockTickScheduler.queueTileEntity((TileEntity) tickable);
    }

    /**
     * Wait for parallel TileEntity ticking to finish AFTER the loop.
     * We target the assignment of processingLoadedTiles = false.
     */
    @Inject(method = "updateEntities", at = @At(value = "FIELD", target = "Lnet/minecraft/world/World;processingLoadedTiles:Z", opcode = 181, ordinal = 0)) // 181 = PUTFIELD
    private void afterTELoop(CallbackInfo ci) {
        if (OtherThreadingConfig.tileEntityThreadingEnabled) {
            BlockTickScheduler.waitForFinish((World)(Object)this);
        }
    }
}
