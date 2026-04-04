package ded.otherthreading.transform.mixin;

import ded.otherthreading.OtherThreadingConfig;
import ded.otherthreading.schedule.BlockTickScheduler;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;

@Mixin(WorldServer.class)
public abstract class MixinWorldServer {

    @Shadow public TreeSet<NextTickListEntry> pendingTickListEntriesTreeSet;
    @Shadow public java.util.Set<NextTickListEntry> pendingTickListEntriesHashSet;
    @Shadow protected abstract void updateBlocks();

    @Shadow protected abstract void playerCheckLight();

    /**
     * @author Antigravity
     * @reason Full replacement for high-throughput parallel ticking.
     */
    @Inject(method = "updateBlocks", at = @At("HEAD"), cancellable = true)
    private void onUpdateBlocks(CallbackInfo ci) {
        if (!OtherThreadingConfig.blockThreadingEnabled) return;
        ci.cancel();

        WorldServer world = (WorldServer)(Object)this;
        this.playerCheckLight();

        // 1. Parallel Random Ticks
        int randomTickSpeed = world.getGameRules().getInt("randomTickSpeed");
        if (randomTickSpeed > 0) {
            for (net.minecraft.world.chunk.Chunk chunk : world.getChunkProvider().getLoadedChunks()) {
                int j = chunk.x * 16;
                int k = chunk.z * 16;
                // Simplified area check for random ticks
                if (world.isAreaLoaded(new BlockPos(j, 0, k), 16)) {
                    // Queue a task for this chunk's random ticks
                    BlockTickScheduler.queueRandomTickTask(chunk, randomTickSpeed);
                }
            }
        }

        // 2. High-Throughput Scheduled Ticks
        synchronized (this.pendingTickListEntriesTreeSet) {
            int totalPending = this.pendingTickListEntriesTreeSet.size();
            if (totalPending > 0) {
                long currentTime = world.getWorldInfo().getWorldTotalTime();
                List<NextTickListEntry> toTick = new ArrayList<>();
                Iterator<NextTickListEntry> it = this.pendingTickListEntriesTreeSet.iterator();
                
                while (it.hasNext()) {
                    NextTickListEntry entry = it.next();
                    if (entry.scheduledTime > currentTime) break;
                    
                    toTick.add(entry);
                    it.remove();
                    this.pendingTickListEntriesHashSet.remove(entry);
                }
                
                if (!toTick.isEmpty()) {
                    for (NextTickListEntry entry : toTick) {
                        BlockTickScheduler.queueBlockTick(entry);
                    }
                }
            }
        }
        
        // Execute everything queued and random ticks (simulated)
        BlockTickScheduler.waitForFinish(world);
    }

    @Inject(method = "scheduleBlockUpdate", at = @At("HEAD"), cancellable = true)
    private void onScheduleBlockUpdate(BlockPos pos, Block blockIn, int delay, int priority, CallbackInfo ci) {
        if (BlockTickScheduler.isWorkerThread()) {
            NextTickListEntry entry = new NextTickListEntry(pos, blockIn);
            entry.setScheduledTime((long)delay + ((WorldServer)(Object)this).getWorldInfo().getWorldTotalTime());
            entry.setPriority(priority);
            
            synchronized (this.pendingTickListEntriesTreeSet) {
                if (!this.pendingTickListEntriesHashSet.contains(entry)) {
                    this.pendingTickListEntriesHashSet.add(entry);
                    this.pendingTickListEntriesTreeSet.add(entry);
                }
            }
            ci.cancel();
        }
    }
}
