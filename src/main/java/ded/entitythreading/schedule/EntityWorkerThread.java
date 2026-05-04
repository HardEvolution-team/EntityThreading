package ded.entitythreading.schedule;

import java.util.ArrayList;

/**
 * @deprecated No longer used. Entity ticking has been migrated to Java 25
 *             <b>virtual threads</b> via {@link java.util.concurrent.Executors#newVirtualThreadPerTaskExecutor()}.
 *             <p>
 *             Per-thread state is now carried by {@link EntityTickContext} bound through
 *             {@link EntityTickScheduler#TICK_CONTEXT} ({@link ScopedValue}).
 *             <p>
 *             This class is retained only to prevent binary-incompatibility breakage in case
 *             any external code (e.g. Mixin plugins from other mods) holds a compiled
 *             reference to this class name at runtime.  It performs no function.
 */
@Deprecated(since = "2.2.0", forRemoval = true)
public final class EntityWorkerThread extends Thread {

    // ---- Stub fields kept for source-level compat only ----
    volatile boolean isWorker  = false;
    volatile boolean isRemote  = false;
    long lastChunkKey          = Long.MIN_VALUE;
    Object lastChunk           = null;
    final ArrayList<Runnable> deferredBuffer = new ArrayList<>(0);

    @Deprecated
    public EntityWorkerThread(Runnable target, String name) {
        super(target, name);
        setDaemon(true);
    }

    /**
     * @deprecated Use {@link EntityTickScheduler#isEntityThread()} instead.
     */
    @Deprecated
    public static boolean isCurrentThreadWorker() {
        // Delegate to the new ScopedValue-based check so any surviving call-sites still work.
        return EntityTickScheduler.isEntityThread();
    }

    /**
     * @deprecated Use {@link EntityTickScheduler#isCurrentThreadRemote()} instead.
     */
    @Deprecated
    public static boolean isCurrentThreadRemote() {
        return EntityTickScheduler.isCurrentThreadRemote();
    }

    @Deprecated
    void resetForTask(boolean remote) {
        this.isWorker = true;
        this.isRemote = remote;
        this.lastChunkKey = Long.MIN_VALUE;
        this.lastChunk = null;
        this.deferredBuffer.clear();
    }

    @Deprecated
    void finishTask() {
        this.isWorker = false;
        this.lastChunk = null;
    }
}
