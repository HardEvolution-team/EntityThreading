package ded.entitythreading.transform.mixin;

import ded.entitythreading.schedule.EntityTickScheduler;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Defers the heavy GZIP compression and disk I/O of MapStorage (scoreboards, villages, maps)
 * to a background thread to prevent extreme TPS lag spikes during server saving.
 */
@Mixin(MapStorage.class)
public class MixinMapStorage {

    @Shadow @Final private ISaveHandler saveHandler;

    /**
     * @author Th3_Sl1ze
     * @reason Considering regular saveData causes severe lag spikes especially on big modpacks (e.g. RTG + BoP generation), I've also made it async.
     * It doesn't eliminate the problem enitrely, but it definitely smoothes out the lag spike (from ~3000 ms to smth like ~1000-1200 ms)
     * I probably should've done a config option for that, eh?..
     */
    @Inject(method = "saveData", at = @At("HEAD"), cancellable = true)
    private void onSaveDataAsync(WorldSavedData data, CallbackInfo ci) {
        if (data != null) {
            NBTTagCompound root = new NBTTagCompound();
            root.setTag("data", data.writeToNBT(new NBTTagCompound()));
            NBTTagCompound wrapper = new NBTTagCompound();
            wrapper.setTag("data", root);

            NBTTagCompound copy = wrapper.copy();

            File saveFile = this.saveHandler.getMapFileFromName(data.mapName);

            if (saveFile != null) {
                String mapName = data.mapName;

                EntityTickScheduler.getSharedPool().execute(() -> {
                    try (FileOutputStream fos = new FileOutputStream(saveFile)) {
                        CompressedStreamTools.writeCompressed(copy, fos);
                    } catch (Exception e) {
                        System.err.println("[EntityThreading] Failed to save async MapStorage data: " + mapName);
                        e.printStackTrace();
                    }
                });
            }

            ci.cancel();
        }
    }
}