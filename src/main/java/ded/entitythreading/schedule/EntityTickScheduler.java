package ded.entitythreading.schedule;

import ded.entitythreading.EntityThreadingMod;
import ded.entitythreading.config.EntityThreadingConfig;
import ded.entitythreading.interfaces.IEntityActivation;
import ded.entitythreading.interfaces.IMixinWorld;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class EntityTickScheduler {

    private static final Set<String> blacklistedClasses = ConcurrentHashMap.newKeySet();
    private static final Set<String> blacklistedModPrefixes = ConcurrentHashMap.newKeySet();
    private static final Set<String> blacklistedModIds = ConcurrentHashMap.newKeySet();
    private static final Set<String> loggedErrorClasses = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<ArrayList<Entity>> parallelEntities =
            ThreadLocal.withInitial(() -> new ArrayList<>(4096));
    private static final ThreadLocal<ArrayList<Entity>> mainThreadEntities =
            ThreadLocal.withInitial(() -> new ArrayList<>(256));
    private static final ThreadLocal<World> currentTickWorld = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> mainThreadWorkerFlag = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> mainThreadRemoteFlag = ThreadLocal.withInitial(() -> false);
    private static final Long2ObjectOpenHashMap<Chunk> EMPTY_SNAPSHOT = new Long2ObjectOpenHashMap<>(0);
    private static final CopyOnWriteArrayList<Thread> activeWorkerThreads = new CopyOnWriteArrayList<>();
    private static final long WORKER_TIMEOUT_MS = 3_000;
    private static final long COOLDOWN_DURATION_MS = 30_000;
    private static final int BATCH_SIZE = 64;
    private static volatile ExecutorService threadPool;
    private static int currentThreadCount = 0;
    private static volatile Long2ObjectOpenHashMap<Chunk> chunkSnapshot = EMPTY_SNAPSHOT;
    private static volatile long cooldownUntil = 0;

    static {
        initThreadPool();
        rebuildBlacklist();
    }

    private static void initThreadPool() {
        int threads = EntityThreadingConfig.getEffectiveThreadCount();
        if (threads == currentThreadCount && threadPool != null && !threadPool.isShutdown()) return;
        if (threadPool != null) threadPool.shutdownNow();

        AtomicInteger counter = new AtomicInteger(0);
        threadPool = new ThreadPoolExecutor(
                threads, threads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> new EntityWorkerThread(r, "EntityThreading-Worker-" + counter.getAndIncrement())
        );

        currentThreadCount = threads;
        System.out.println("[EntityThreading] Thread pool: " + threads + " workers");
    }

    public static void rebuildBlacklist() {
        blacklistedClasses.clear();
        blacklistedModPrefixes.clear();
        blacklistedModIds.clear();

        blacklistedClasses.add("net.minecraft.entity.item.EntityItem");
        blacklistedClasses.add("net.minecraft.entity.item.EntityXPOrb");

        if (EntityThreadingConfig.blacklistedEntities != null) {
            for (String cls : EntityThreadingConfig.blacklistedEntities) {
                if (cls != null && !cls.trim().isEmpty()) blacklistedClasses.add(cls.trim());
            }
        }

        blacklistedModIds.add("hbm");
        blacklistedModPrefixes.add("com.hbm.");
    }

    public static boolean isModEventBlacklisted(String modId) {
        return blacklistedModIds.contains(modId);
    }

    private static boolean isBlacklisted(Entity entity) {
        String className = entity.getClass().getName();
        if (blacklistedClasses.contains(className)) return true;
        for (String prefix : blacklistedModPrefixes) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    public static void queueEntity(World world, Entity entity) {
        if (!EntityThreadingConfig.enabled || world.isRemote) {
            ((IMixinWorld) world).entitythreading$tickEntityDirectly(entity);
            return;
        }

        currentTickWorld.set(world);

        if (entity instanceof EntityPlayer || isBlacklisted(entity)) {
            mainThreadEntities.get().add(entity);
        } else {
            parallelEntities.get().add(entity);
        }
    }

    public static void waitForFinish() {
        if (!EntityThreadingConfig.enabled) return;

        ArrayList<Entity> parallel = parallelEntities.get();
        ArrayList<Entity> mainThread = mainThreadEntities.get();
        World world = currentTickWorld.get();

        if (world == null) return;
        boolean isRemote = world.isRemote;

        mainThreadRemoteFlag.set(isRemote);
        mainThreadWorkerFlag.set(false);

        if (EntityThreadingConfig.entityActivationRange && !isRemote) {
            EntityActivationRange.activateEntities(world);
        }

        tickMainThreadEntities(world, mainThread);

        int parallelCount = parallel.size();
        if (parallelCount > 0) {
            boolean inCooldown = System.currentTimeMillis() < cooldownUntil;
            if (parallelCount < EntityThreadingConfig.minEntitiesForThreading || inCooldown) {
                tickAllOnMainThread(world, parallel);
            } else {
                tickParallel(world, parallel, isRemote);
            }
        }

        DeferredActionQueue.replayAll(isRemote);
        currentTickWorld.remove();
    }

    private static void tickMainThreadEntities(World world, ArrayList<Entity> entries) {
        if (entries.isEmpty()) return;
        for (Entity entry : entries) {
            try {
                world.updateEntity(entry);
            } catch (Exception e) {
                logError(entry, e);
            }
        }
        entries.clear();
    }

    private static void tickAllOnMainThread(World world, ArrayList<Entity> entries) {
        for (Entity entry : entries) {
            try {
                world.updateEntity(entry);
            } catch (Exception e) {
                logError(entry, e);
            }
        }
        entries.clear();
    }

    private static void buildChunkSnapshot(World world) {
        try {
            ChunkProviderServer provider = (ChunkProviderServer) world.getChunkProvider();
            Collection<Chunk> loaded = provider.getLoadedChunks();
            Long2ObjectOpenHashMap<Chunk> snap = new Long2ObjectOpenHashMap<>(loaded.size() + 16);
            for (Chunk chunk : loaded) {
                snap.put(ChunkPos.asLong(chunk.x, chunk.z), chunk);
            }
            chunkSnapshot = snap;
        } catch (Exception e) {
            System.err.println("[EntityThreading] Failed to build chunk snapshot: " + e.getMessage());
            chunkSnapshot = EMPTY_SNAPSHOT;
        }
    }

    public static Chunk getChunkFromSnapshot(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        Thread t = Thread.currentThread();
        if (t instanceof EntityWorkerThread ewt) {
            if (ewt.lastChunkKey == key && ewt.lastChunk != null) {
                return (Chunk) ewt.lastChunk;
            }
            Chunk c = chunkSnapshot.get(key);
            ewt.lastChunkKey = key;
            ewt.lastChunk = c;
            return c;
        }
        return chunkSnapshot.get(key);
    }

    private static void tickParallel(World world, ArrayList<Entity> entries, boolean isRemote) {
        int totalEntities = entries.size();
        Entity[] tickArray = entries.toArray(new Entity[0]);
        entries.clear();

        buildChunkSnapshot(world);

        int workerCount = currentThreadCount;
        final AtomicInteger currentIndex = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(workerCount);
        activeWorkerThreads.clear();

        for (int t = 0; t < workerCount; t++) {
            threadPool.execute(() -> {
                Thread ct = Thread.currentThread();
                activeWorkerThreads.add(ct);
                EntityWorkerThread ewt = (EntityWorkerThread) ct;
                ewt.resetForTask(isRemote);
                try {
                    int idx;
                    while ((idx = currentIndex.getAndAdd(BATCH_SIZE)) < totalEntities) {
                        int end = Math.min(idx + BATCH_SIZE, totalEntities);
                        for (int j = idx; j < end; j++) {
                            safeTick(world, tickArray[j]);
                        }
                    }
                } finally {
                    ArrayList<Runnable> buf = ewt.deferredBuffer;
                    if (!buf.isEmpty()) {
                        DeferredActionQueue.submitWorkerBuffer(new ArrayList<>(buf));
                        buf.clear();
                    }
                    ewt.finishTask();
                    activeWorkerThreads.remove(ct);
                    latch.countDown();
                }
            });
        }

        mainThreadWorkerFlag.set(true);
        mainThreadRemoteFlag.set(isRemote);
        try {
            int idx;
            while ((idx = currentIndex.getAndAdd(BATCH_SIZE)) < totalEntities) {
                int end = Math.min(idx + BATCH_SIZE, totalEntities);
                for (int j = idx; j < end; j++) {
                    safeTick(world, tickArray[j]);
                }
            }
        } finally {
            mainThreadWorkerFlag.set(false);
        }

        try {
            if (!latch.await(WORKER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                int stuckCount = (int) latch.getCount();
                EntityThreadingMod.LOGGER.warn("{} worker(s) stuck after " + WORKER_TIMEOUT_MS + "ms!", stuckCount);
                for (Thread worker : activeWorkerThreads) {
                    EntityThreadingMod.LOGGER.error("Stuck thread: {}", worker.getName());
                    for (StackTraceElement ste : worker.getStackTrace()) {
                        EntityThreadingMod.LOGGER.error("at {}", ste);
                    }
                }
                activeWorkerThreads.clear();
                cooldownUntil = System.currentTimeMillis() + COOLDOWN_DURATION_MS;
                EntityThreadingMod.LOGGER.error("Parallel ticking disabled for " + (COOLDOWN_DURATION_MS / 1000) + "s cooldown.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        chunkSnapshot = EMPTY_SNAPSHOT;
    }

    private static void safeTick(World world, Entity entity) {
        if (entity.isDead) return;

        boolean useActivation = EntityThreadingConfig.entityActivationRange && !world.isRemote;
        if (useActivation && !((IEntityActivation) entity).entitythreading$isActive()) {
            minimalTick(entity);
            return;
        }

        int oldCX = entity.chunkCoordX;
        int oldCY = entity.chunkCoordY;
        int oldCZ = entity.chunkCoordZ;

        try {
            entity.prevPosX = entity.posX;
            entity.prevPosY = entity.posY;
            entity.prevPosZ = entity.posZ;

            if (!entity.isRiding()) {
                entity.lastTickPosX = entity.posX;
                entity.lastTickPosY = entity.posY;
                entity.lastTickPosZ = entity.posZ;
                entity.prevRotationYaw = entity.rotationYaw;
                entity.prevRotationPitch = entity.rotationPitch;
            }

            if (entity.addedToChunk || entity.forceSpawn) {
                ++entity.ticksExisted;
                if (entity.isRiding()) {
                    entity.updateRidden();
                } else {
                    entity.onUpdate();
                }
            }
        } catch (Throwable t) {
            if (loggedErrorClasses.add(entity.getClass().getName())) {
                EntityThreadingMod.LOGGER.error("{} tick error: {}", entity.getClass().getSimpleName(), t.getMessage());
                t.printStackTrace();
            }
            return;
        }

        tickPassengers(world, entity);

        int newCX = MathHelper.floor(entity.posX / 16.0D);
        int newCY = MathHelper.floor(entity.posY / 16.0D);
        int newCZ = MathHelper.floor(entity.posZ / 16.0D);

        if (newCX != oldCX || newCY != oldCY || newCZ != oldCZ) {
            DeferredActionQueue.enqueue(() -> ((IMixinWorld) world).entitythreading$updateChunkPos(entity));
        }
    }

    private static void minimalTick(Entity entity) {
        ++entity.ticksExisted;
        entity.prevPosX = entity.posX;
        entity.prevPosY = entity.posY;
        entity.prevPosZ = entity.posZ;
        if (entity.isBurning()) {
            entity.extinguish();
        }
    }


    private static void tickPassengers(World world, Entity entity) {
        try {
            List<Entity> passengers = entity.getPassengers();
            if (passengers.isEmpty()) return;
            Entity[] passArray = passengers.toArray(new Entity[0]);
            for (Entity passenger : passArray) {
                if (passenger.isDead || passenger.getRidingEntity() != entity) {
                    DeferredActionQueue.enqueue(() -> {
                        try {
                            passenger.dismountRidingEntity();
                        } catch (Exception ignored) {
                        }
                    });
                    continue;
                }
                if (passenger instanceof EntityPlayer || isBlacklisted(passenger)) {
                    DeferredActionQueue.enqueue(() -> {
                        try {
                            world.updateEntity(passenger);
                        } catch (Exception ignored) {
                        }
                    });
                } else {
                    safeTick(world, passenger);
                }
            }
        } catch (Throwable t) {
            if (loggedErrorClasses.add(entity.getClass().getName() + "_passengers")) {
                t.printStackTrace();
            }
        }
    }

    private static void logError(Entity entity, Exception e) {
        if (loggedErrorClasses.add(entity.getClass().getName())) {
            EntityThreadingMod.LOGGER.error("Tick error: {}: {}", entity.getClass().getSimpleName(), e.getMessage());
        }
    }

    public static boolean isEntityThread() {
        if (EntityWorkerThread.isCurrentThreadWorker()) return true;
        return mainThreadWorkerFlag.get();
    }

    public static boolean isCurrentThreadRemote() {
        Thread t = Thread.currentThread();
        if (t instanceof EntityWorkerThread) {
            return ((EntityWorkerThread) t).isRemote;
        }
        return mainThreadRemoteFlag.get();
    }

    public static ExecutorService getSharedPool() {
        return threadPool;
    }

    public static void reinitialize() {
        initThreadPool();
        rebuildBlacklist();
    }

    public static void shutdown() {
        if (threadPool != null) threadPool.shutdownNow();
        parallelEntities.get().clear();
        mainThreadEntities.get().clear();
        DeferredActionQueue.clear();
        AsyncPathProcessor.shutdown();
        chunkSnapshot = EMPTY_SNAPSHOT;
        loggedErrorClasses.clear();
    }
}
