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
import java.util.concurrent.ExecutorService;

@Mixin(MapStorage.class)
public class MixinMapStorage {

    @Shadow @Final private ISaveHandler saveHandler;

    @Inject(method = "saveData", at = @At("HEAD"), cancellable = true)
    private void onSaveDataAsync(WorldSavedData data, CallbackInfo ci) {
        if (data == null) return;

        NBTTagCompound root = new NBTTagCompound();
        root.setTag("data", data.writeToNBT(new NBTTagCompound()));
        NBTTagCompound wrapper = new NBTTagCompound();
        wrapper.setTag("data", root);
        NBTTagCompound copy = wrapper.copy();

        File saveFile = this.saveHandler.getMapFileFromName(data.mapName);
        if (saveFile == null) return;

        ExecutorService pool = EntityTickScheduler.getSharedPool();
        if (pool == null || pool.isShutdown()) return;

        pool.execute(() -> {
            try (FileOutputStream fos = new FileOutputStream(saveFile)) {
                CompressedStreamTools.writeCompressed(copy, fos);
            } catch (Exception e) {
                System.err.println("[EntityThreading] Failed to save MapStorage: " + data.mapName);
            }
        });

        ci.cancel();
    }
}
