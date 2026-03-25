package ded.entitythreading.schedule;

import ded.entitythreading.EntityThreadingConfig;
import ded.entitythreading.transform.IMixinWorld;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class EntityTickScheduler {

    private static volatile ExecutorService threadPool;
    private static int currentThreadCount = 0;

    // Blacklisted entity class names
    private static final Set<String> blacklistedClasses = ConcurrentHashMap.newKeySet();
    private static final Set<String> blacklistedModIds = ConcurrentHashMap.newKeySet();
    private static final Set<String> loggedErrorClasses = ConcurrentHashMap.newKeySet();

    // Entity collection buffers — ThreadLocal for client/server isolation
    private static final ThreadLocal<List<EntityTickEntry>> parallelEntities =
            ThreadLocal.withInitial(() -> new ArrayList<>(4096));
    private static final ThreadLocal<List<EntityTickEntry>> mainThreadEntities =
            ThreadLocal.withInitial(() -> new ArrayList<>(256));

    // ThreadLocal flag: true if current thread is one of our worker threads
    private static final ThreadLocal<Boolean> IS_WORKER_THREAD = ThreadLocal.withInitial(() -> false);

    // Track current side for deferred action queue routing
    private static final ThreadLocal<Boolean> currentSideIsRemote = ThreadLocal.withInitial(() -> false);

    /**
     * Snapshot of loaded chunks, built on main thread before parallel ticking.
     * Uses FastUtil's Long2ObjectOpenHashMap to eliminate primitive boxing overhead.
     * That's probably the fastest I could do here..
     */
    private static final Long2ObjectOpenHashMap<Chunk> EMPTY_SNAPSHOT = new Long2ObjectOpenHashMap<>(0);
    private static volatile Long2ObjectOpenHashMap<Chunk> chunkSnapshot = EMPTY_SNAPSHOT;

    // Track active worker threads so we can dump their stacks on timeout
    private static final CopyOnWriteArrayList<Thread> activeWorkerThreads = new CopyOnWriteArrayList<>();

    private static final String THREAD_NAME_PREFIX = "EntityThreading-Worker-";

    // Safety timeout — 3 seconds is long enough for any reasonable entity tick
    private static final long WORKER_TIMEOUT_MS = 3_000;

    // Cooldown after timeout — disable parallel ticking temporarily
    private static volatile long cooldownUntil = 0;
    private static final long COOLDOWN_DURATION_MS = 30_000; // 30 seconds

    static {
        initThreadPool();
        rebuildBlacklist();
    }

    private static void initThreadPool() {
        int threads = EntityThreadingConfig.getEffectiveThreadCount();
        if (threads == currentThreadCount && threadPool != null && !threadPool.isShutdown()) {
            return;
        }
        if (threadPool != null) {
            threadPool.shutdownNow();
        }

        threadPool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r);
            t.setName(THREAD_NAME_PREFIX + t.getId());
            t.setDaemon(true);
            return t;
        });

        currentThreadCount = threads;
        System.out.println("[EntityThreading] Thread pool: " + threads + " workers");
    }

    public static void rebuildBlacklist() {
        blacklistedClasses.clear();
        blacklistedModIds.clear();

        // HARDCODED — these MUST always be on main thread regardless of config
        // EntityItem: Quark hooks EntityItem.onUpdate() with WeakHashMap (not thread-safe)
        // EntityXPOrb: Similar lightweight entity, no benefit from parallel ticking
        blacklistedClasses.add("net.minecraft.entity.item.EntityItem");
        blacklistedClasses.add("net.minecraft.entity.item.EntityXPOrb");

        if (EntityThreadingConfig.blacklistedEntities != null) {
            for (String cls : EntityThreadingConfig.blacklistedEntities) {
                if (cls != null && !cls.trim().isEmpty()) blacklistedClasses.add(cls.trim());
            }
        }

        blacklistedModIds.add("hbm");
    }

    public static boolean isModEventBlacklisted(String modId) {
        return blacklistedModIds.contains(modId);
    }

    public static void queueEntity(World world, Entity entity) {
        if (!EntityThreadingConfig.enabled || world.isRemote) {
            // Disabled or client-side — tick directly on main thread via vanilla
            ((IMixinWorld) world).entitythreading$tickEntityDirectly(entity);
            return;
        }

        if (entity instanceof EntityPlayer || blacklistedClasses.contains(entity.getClass().getName())) {
            mainThreadEntities.get().add(new EntityTickEntry(world, entity));
            return;
        }

        // tbh NTM has enough quirks to deal with, I'd rather let it do it's own work as it alerady has multi-threading radiation e.g.
        // we don't want to multi-thread multi-threading, right?
        if (entity.getClass().getName().startsWith("com.hbm.")) {
            mainThreadEntities.get().add(new EntityTickEntry(world, entity));
            return;
        }

        parallelEntities.get().add(new EntityTickEntry(world, entity));
    }

    /**
     * Called by ASM hook at the end of World.updateEntities().
     * Ticks all queued entities and replays deferred actions.
     */
    public static void waitForFinish() {
        if (!EntityThreadingConfig.enabled) return;

        List<EntityTickEntry> parallel = parallelEntities.get();
        List<EntityTickEntry> mainThread = mainThreadEntities.get();

        // Determine side
        boolean isRemote;
        if (!parallel.isEmpty()) isRemote = parallel.get(0).world.isRemote;
        else if (!mainThread.isEmpty()) isRemote = mainThread.get(0).world.isRemote;
        else return;

        // Set ThreadLocals for main thread in case it runs safeTick (e.g. during replay or fallback)
        currentSideIsRemote.set(isRemote);
        IS_WORKER_THREAD.set(false);

        // 1. Tick main-thread-only entities first (players, blacklisted)
        tickMainThreadEntities(mainThread);

        // 2. Parallel tick (or main-thread-only if below threshold or in cooldown)
        int parallelCount = parallel.size();
        if (parallelCount > 0) {
            boolean inCooldown = System.currentTimeMillis() < cooldownUntil;
            if (parallelCount < EntityThreadingConfig.minEntitiesForThreading || inCooldown) {
                // Below threshold or cooling down after timeout — tick on main thread
                tickAllOnMainThread(parallel);
            } else {
                tickParallel(parallel, isRemote);
            }
        }

        // 3. Replay ALL deferred world mutations on main thread
        int deferred = DeferredActionQueue.replayAll(isRemote);

        if (EntityThreadingConfig.debugLogging) {
            System.out.println("[EntityThreading] Ticked " + (mainThread.size() + parallelCount) +
                    " entities, " + deferred + " deferred on " + (isRemote ? "Client" : "Server"));
        }
    }

    private static void tickMainThreadEntities(List<EntityTickEntry> entries) {
        if (entries.isEmpty()) return;

        for (EntityTickEntry entry : entries) {
            try {
                // Use vanilla updateEntity — safe on main thread
                entry.world.updateEntity(entry.entity);
            } catch (Exception e) {
                System.err.println("[EntityThreading] Main thread tick error: " +
                        entry.entity.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        entries.clear();
    }

    private static void tickAllOnMainThread(List<EntityTickEntry> entries) {
        for (EntityTickEntry entry : entries) {
            try {
                entry.world.updateEntity(entry.entity);
            } catch (Exception e) {
                if (loggedErrorClasses.add(entry.entity.getClass().getName())) {
                    System.err.println("[EntityThreading] Tick error: " +
                            entry.entity.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }
        entries.clear();
    }

    /**
     * Build a snapshot of all loaded chunks for the given world.
     * Called on the main thread before starting worker threads.
     * Workers use this snapshot instead of touching ChunkProvider directly.
     */

    private static void buildChunkSnapshot(World world) {
        Long2ObjectOpenHashMap<Chunk> snapshot = new Long2ObjectOpenHashMap<>(1024);
        try {
            ChunkProviderServer provider = (ChunkProviderServer) world.getChunkProvider();
            for (Chunk chunk : provider.getLoadedChunks()) {
                snapshot.put(ChunkPos.asLong(chunk.x, chunk.z), chunk);
            }
        } catch (Exception e) {
            System.err.println("[EntityThreading] Failed to build chunk snapshot: " + e.getMessage());
        }
        chunkSnapshot = snapshot;
    }

    /**
     * Get a chunk from the pre-built snapshot. Used by MixinWorld for worker threads.
     * @return the chunk, or null if not in the snapshot
     */
    public static Chunk getChunkFromSnapshot(int x, int z) {
        return chunkSnapshot.get(ChunkPos.asLong(x, z));
    }

    /**
     * Core parallel ticking with main thread participation.
     * Main thread takes a batch too — no idle waiting.
     */
    private static void tickParallel(List<EntityTickEntry> entries, boolean isRemote) {
        EntityTickEntry[] tickArray = entries.toArray(new EntityTickEntry[0]);
        entries.clear();

        int totalEntities = tickArray.length;
        int workerCount = currentThreadCount;

        // Build chunk snapshot BEFORE starting workers (main thread, safe)
        buildChunkSnapshot(tickArray[0].world);

        // We use a shared atomic index so threads (including main) can steal work dynamically.
        // This completely eliminates load imbalance and puts the main thread to work,
        // preventing the massive latch.await() bottleneck shown in profilers.
        final AtomicInteger currentIndex = new AtomicInteger(0);

        CountDownLatch latch = new CountDownLatch(workerCount);
        activeWorkerThreads.clear();

        // Submit to worker threads
        for (int t = 0; t < workerCount; t++) {
            threadPool.execute(() -> {
                Thread currentThread = Thread.currentThread();
                activeWorkerThreads.add(currentThread);
                IS_WORKER_THREAD.set(true);
                currentSideIsRemote.set(isRemote);
                try {
                    int i;
                    while ((i = currentIndex.getAndAdd(32)) < totalEntities) {
                        int end = Math.min(i + 32, totalEntities);
                        for (int j = i; j < end; j++) {
                            safeTick(tickArray[j].world, tickArray[j].entity);
                        }
                    }
                } finally {
                    IS_WORKER_THREAD.set(false);
                    activeWorkerThreads.remove(currentThread);
                    latch.countDown();
                }
            });
        }

        IS_WORKER_THREAD.set(true);
        currentSideIsRemote.set(isRemote);
        try {
            int i;
            while ((i = currentIndex.getAndAdd(32)) < totalEntities) {
                int end = Math.min(i + 32, totalEntities);
                for (int j = i; j < end; j++) {
                    safeTick(tickArray[j].world, tickArray[j].entity);
                }
            }
        } finally {
            IS_WORKER_THREAD.set(false);
        }

        // Main thread ONLY waits for workers to finish their final active batches
        try {
            if (!latch.await(WORKER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                // Workers are stuck — dump their stack traces for diagnostics
                int stuckCount = (int) latch.getCount();
                System.err.println("[EntityThreading] WARNING: " + stuckCount +
                        " worker(s) stuck after " + WORKER_TIMEOUT_MS + "ms!");
                for (Thread worker : activeWorkerThreads) {
                    System.err.println("[EntityThreading] Stuck thread: " + worker.getName());
                    for (StackTraceElement ste : worker.getStackTrace()) {
                        System.err.println("    at " + ste);
                    }
                }
                activeWorkerThreads.clear();

                // Enter cooldown — disable parallel ticking for 30 seconds
                // DO NOT recreate pool — that leaks spinning threads and causes 100% CPU
                cooldownUntil = System.currentTimeMillis() + COOLDOWN_DURATION_MS;
                System.err.println("[EntityThreading] Parallel ticking disabled for " +
                        (COOLDOWN_DURATION_MS / 1000) + "s cooldown. Entities will tick on main thread.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Release chunk snapshot
        chunkSnapshot = EMPTY_SNAPSHOT;
    }

    /**
     * Safely tick a single entity WITHOUT calling world.updateEntity().
     *
     * entity.onUpdate() may still access world.getBlockState() → getChunk(),
     * but MixinWorld redirects getChunk() calls from entity threads to use
     * our chunk snapshot instead of the non-thread-safe ChunkProvider.
     */
    private static void safeTick(World world, Entity entity) {
        int oldCX = entity.chunkCoordX;
        int oldCY = entity.chunkCoordY;
        int oldCZ = entity.chunkCoordZ;

        try {
            // Replicate vanilla World.updateEntity() pre-tick logic
            if (!entity.isRiding()) {
                entity.lastTickPosX = entity.posX;
                entity.lastTickPosY = entity.posY;
                entity.lastTickPosZ = entity.posZ;
                entity.prevRotationYaw = entity.rotationYaw;
                entity.prevRotationPitch = entity.rotationPitch;
            }

            if (entity.addedToChunk || entity.forceSpawn) {
                if (entity.isRiding()) {
                    entity.updateRidden();
                } else {
                    entity.onUpdate();
                }
            }
        } catch (Throwable t) {
            // Log once per class, continue — do NOT blacklist
            if (loggedErrorClasses.add(entity.getClass().getName())) {
                System.err.println("[EntityThreading] " +
                        entity.getClass().getSimpleName() + " tick error (continuing): " + t.getMessage());
                t.printStackTrace();
            }
            return;
        }

        try {
            List<Entity> passengers = entity.getPassengers();
            if (!passengers.isEmpty()) {
                Entity[] passArray = passengers.toArray(new Entity[0]);
                for (Entity passenger : passArray) {
                    if (!passenger.isDead && passenger.getRidingEntity() == entity) {
                        if (passenger instanceof EntityPlayer || blacklistedClasses.contains(passenger.getClass().getName())) {
                            DeferredActionQueue.enqueue(() -> safeTick(world, passenger));
                        } else {
                            safeTick(world, passenger);
                        }
                    } else {
                        DeferredActionQueue.enqueue(() -> {
                            try { passenger.dismountRidingEntity(); } catch (Exception e) {}
                        });
                    }
                }
            }
        } catch (Throwable t) {
            if (loggedErrorClasses.add(entity.getClass().getName() + "_passenger_loop")) {
                t.printStackTrace();
            }
        }

        // Detect chunk boundary crossing → defer to main thread
        int newCX = MathHelper.floor(entity.posX / 16.0D);
        int newCY = MathHelper.floor(entity.posY / 16.0D);
        int newCZ = MathHelper.floor(entity.posZ / 16.0D);

        if (newCX != oldCX || newCY != oldCY || newCZ != oldCZ) {
            DeferredActionQueue.enqueue(() -> ((IMixinWorld) world).entitythreading$updateChunkPos(entity));
        }
    }

    /**
     * Returns true if current thread is a worker thread from our pool.
     */
    public static boolean isEntityThread() {
        return IS_WORKER_THREAD.get();
    }

    /**
     * Returns the side (Client vs Server) the current thread is processing.
     */
    public static boolean isCurrentThreadRemote() {
        return currentSideIsRemote.get();
    }

    /**
     * Get the shared thread pool for use by other components.
     */
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
        chunkSnapshot = EMPTY_SNAPSHOT;
    }

    private static class EntityTickEntry {
        final World world;
        final Entity entity;

        EntityTickEntry(World world, Entity entity) {
            this.world = world;
            this.entity = entity;
        }
    }
}