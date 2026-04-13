package ded.entitythreading.mixin;

import ded.entitythreading.EntityThreadingMod;
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

/**
 * Offloads MapStorage save operations to the shared thread pool.
 * <p>
 * Uses atomic write pattern: write to temp file, then rename.
 * This prevents data corruption on crash during write.
 */
@Mixin(MapStorage.class)
public abstract class MapStorageMixin {

    @Shadow
    @Final
    private ISaveHandler saveHandler;

    @Inject(method = "saveData", at = @At("HEAD"), cancellable = true)
    private void onSaveDataAsync(WorldSavedData data, CallbackInfo ci) {
        if (data == null) {
            return;
        }

        File saveFile = this.saveHandler.getMapFileFromName(data.mapName);
        if (saveFile == null) {
            return;
        }

        ExecutorService pool = EntityTickScheduler.getSharedPool();
        if (pool == null || pool.isShutdown()) {
            return; // Fall through to vanilla save
        }

        // Build the NBT structure matching vanilla format: root -> "data" -> actual data
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("data", data.writeToNBT(new NBTTagCompound()));

        // Deep copy to ensure thread safety — the original NBT may be mutated
        NBTTagCompound copy = root.copy();

        // Mark as not dirty BEFORE async save — vanilla does this after save,
        // but we need to prevent duplicate saves on next tick
        data.setDirty(false);

        // Capture mapName for error logging (avoid accessing data in async context)
        String mapName = data.mapName;

        pool.execute(() -> {
            // Atomic write: write to temp file, then rename
            File tempFile = new File(saveFile.getParentFile(), saveFile.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                CompressedStreamTools.writeCompressed(copy, fos);
            } catch (Exception e) {
                EntityThreadingMod.LOGGER.error("Failed to save MapStorage '{}': {}",
                        mapName, e.getMessage());
                tempFile.delete();
                return;
            }

            // Atomic rename
            if (!tempFile.renameTo(saveFile)) {
                // On Windows, renameTo fails if target exists — delete first
                saveFile.delete();
                if (!tempFile.renameTo(saveFile)) {
                    EntityThreadingMod.LOGGER.error("Failed to rename temp file for MapStorage '{}'",
                            mapName);
                }
            }
        });

        ci.cancel();
    }
}
