package me.jellysquid.mods.sodium.client.render.chunk.compile;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.model.vertex.type.ChunkVertexType;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkGraphicsState;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderBackend;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderContainer;
import me.jellysquid.mods.sodium.client.render.chunk.passes.BlockRenderPassManager;
import me.jellysquid.mods.sodium.client.render.chunk.tasks.ChunkRenderBuildTask;
import me.jellysquid.mods.sodium.client.render.chunk.tasks.ChunkRenderEmptyBuildTask;
import me.jellysquid.mods.sodium.client.render.chunk.tasks.ChunkRenderRebuildTask;
import me.jellysquid.mods.sodium.client.render.pipeline.context.ChunkRenderCacheLocal;
import me.jellysquid.mods.sodium.client.util.task.CancellationSource;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import me.jellysquid.mods.sodium.client.world.cloned.ChunkRenderContext;
import me.jellysquid.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import me.jellysquid.mods.sodium.common.util.collections.DequeDrain;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

public class ChunkBuilder<T extends ChunkGraphicsState> {
    /**
     * The maximum number of jobs that can be queued for a given worker thread.
     */
    private static final int TASK_QUEUE_LIMIT_PER_WORKER = 2;

    private static final Logger LOGGER = LogManager.getLogger("ChunkBuilder");

    private final Deque<WrappedTask<T>> buildQueue = new ConcurrentLinkedDeque<>();
    private final Deque<ChunkBuildResult<T>> uploadQueue = new ConcurrentLinkedDeque<>();

    private final Object jobNotifier = new Object();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Thread> threads = new ArrayList<>();

    private ClonedChunkSectionCache sectionCache;

    private World world;
    private BlockRenderPassManager renderPassManager;

    // This is the initial number of threads we spin up upon ChunkBuilder creation.
    // Default: 2-4 depending on render distance. *Always* less than hardLimitThreads.
    private final int initialThreads;
    // This is the number of threads we want to 'quickly' get up to. This is calculated
    // by getOptimalThreadCount, but can also be configured by the user. Always less than hardLimitThreads.
    private final int targetThreads;
    // This is the number of threads we are allowed to create in total.
    // This is defaulted to targetThreads, but maxes out at 64. Testing would be required to determine what
    // actual count is optimal for a specific user and use case.
    private final int hardLimitThreads;
    // This is the initial time when this builder is created. We use this to create more threads.
    private long lastThreadAddition;
    // This is the user-configurable time delta to create a new thread, up until targetThreads.
    private final long quickThreadCreationInterval;
    // This is the user-configurable time delta to create a new thread, up until hardLimitThreads.
    private final long slowThreadCreationInterval;

    private final ChunkVertexType vertexType;
    private final ChunkRenderBackend<T> backend;

    public ChunkBuilder(ChunkVertexType vertexType, ChunkRenderBackend<T> backend) {
        this.vertexType = vertexType;
        this.backend = backend;

        // User-configurable options for chunk threads.
        int desiredTargetThreads = SodiumClientMod.options().advanced.targetChunkThreads;
        int desiredInitialThreads = SodiumClientMod.options().advanced.initialChunkThreads;

        // These are bounded by the options configuration. Both are measured in milliseconds.
        this.quickThreadCreationInterval = SodiumClientMod.options().advanced.quickThreadCreationInterval;
        this.slowThreadCreationInterval = SodiumClientMod.options().advanced.slowThreadCreationInterval;

        // Our hard limit of threads. Cap user config at 64, prefer desiredMaxThreads, otherwise use logical core count.
        this.hardLimitThreads = getMaxThreadCount();
        // Our targeted number of threads.
        this.targetThreads = Math.min(desiredTargetThreads == 0 ? getDefaultTargetThreads() : desiredTargetThreads, this.hardLimitThreads);
        // Our initial threads. A bit of a silly calculation for this one.
        this.initialThreads = Math.min(desiredInitialThreads == 0 ? getDefaultInitialThreads() : desiredInitialThreads, this.targetThreads);
    }

    private static int getDefaultTargetThreads() {
        return MathHelper.clamp(Math.max(getLogicalCoreCount() / 3, getLogicalCoreCount() - 6), 1, 10);
    }

    private static int getDefaultInitialThreads() {
        return (SodiumWorldRenderer.getInstance().getRenderDistance() / 10) + 2;
    }

    private static int getLogicalCoreCount() {
        return Runtime.getRuntime().availableProcessors();
    }

    // Split out this function so that SeedQueue can inject into this.
    private static int getMaxThreadCount() {
        int desiredMaxThreads = SodiumClientMod.options().advanced.maxChunkThreads;
        return desiredMaxThreads == 0 ? getLogicalCoreCount() : desiredMaxThreads;
    }

    /**
     * Returns the remaining number of build tasks which should be scheduled this frame. If an attempt is made to
     * spawn more tasks than the budget allows, it will block until resources become available.
     */
    public int getSchedulingBudget() {
        return Math.max(0, (Math.max(this.threads.size(), this.targetThreads) * TASK_QUEUE_LIMIT_PER_WORKER) - this.buildQueue.size());
    }

    public void createWorker(MinecraftClient client) {
        ChunkBuildBuffers buffers = new ChunkBuildBuffers(this.vertexType, this.renderPassManager);
        ChunkRenderCacheLocal pipeline = new ChunkRenderCacheLocal(client, this.world);

        WorkerRunnable worker = new WorkerRunnable(buffers, pipeline);

        Thread thread = new Thread(worker, "Chunk Render Task Executor #" + this.threads.size() + 1);
        thread.setPriority(Math.max(0, Thread.NORM_PRIORITY - 2));
        thread.start();

        this.threads.add(thread);

        // Helper debug message. Prints at most once per reload, so shouldn't noticeably increase log spam.
        if (this.threads.size() == this.hardLimitThreads) {
            LOGGER.info("Reached maximum Sodium builder threads of {}", this.hardLimitThreads);
        }
        this.lastThreadAddition = System.currentTimeMillis();
    }

    /**
     * Spawns a number of work-stealing threads to process results in the build queue. If the builder is already
     * running, this method does nothing and exits.
     */
    public void startWorkers() {
        if (this.running.getAndSet(true)) {
            return;
        }

        if (!this.threads.isEmpty()) {
            throw new IllegalStateException("Threads are still alive while in the STOPPED state");
        }

        MinecraftClient client = MinecraftClient.getInstance();
        for (int i = 0; i < this.initialThreads; i++) {
            this.createWorker(client);
        }

        LOGGER.info("Started {} worker threads", this.threads.size());
    }

    /**
     * Spawns workers if we have thread space.
     */
    public void createMoreThreads() {
        if (this.threads.size() >= this.hardLimitThreads) {
            return;
        }

        long timeDelta = System.currentTimeMillis() - this.lastThreadAddition;
        if (this.threads.size() < this.targetThreads) {
            // Check if enough time has elapsed for us to create a target thread.
            if (timeDelta > this.quickThreadCreationInterval) {
                this.createWorker(MinecraftClient.getInstance());
            }
        }
        // Check if enough time has elapsed for us to create a target thread.
        else if (timeDelta > this.slowThreadCreationInterval) {
            this.createWorker(MinecraftClient.getInstance());
        }
    }

    /**
     * Notifies all worker threads to stop and blocks until all workers terminate. After the workers have been shut
     * down, all tasks are cancelled and the pending queues are cleared. If the builder is already stopped, this
     * method does nothing and exits.
     */
    public void stopWorkers() {
        if (!this.running.getAndSet(false)) {
            return;
        }

        if (this.threads.isEmpty()) {
            throw new IllegalStateException("No threads are alive but the executor is in the RUNNING state");
        }

        LOGGER.info("Stopping worker threads");

        // Notify all worker threads to wake up, where they will then terminate
        synchronized (this.jobNotifier) {
            this.jobNotifier.notifyAll();
        }

        // Wait for every remaining thread to terminate
        for (Thread thread : this.threads) {
            try {
                thread.join();
            } catch (InterruptedException ignored) {
            }
        }

        this.threads.clear();

        // Drop any pending work queues and cancel futures
        this.uploadQueue.clear();

        for (WrappedTask<?> job : this.buildQueue) {
            job.future.cancel(true);
        }

        this.buildQueue.clear();

        this.world = null;
        this.sectionCache = null;
    }

    /**
     * Processes all pending build task uploads using the chunk render backend.
     */
    // TODO: Limit the amount of time this can take per frame
    public boolean performPendingUploads() {
        if (this.uploadQueue.isEmpty()) {
            return false;
        }

        this.backend.upload(RenderDevice.INSTANCE.createCommandList(), new DequeDrain<>(this.uploadQueue));

        return true;
    }

    public CompletableFuture<ChunkBuildResult<T>> schedule(ChunkRenderBuildTask<T> task) {
        if (!this.running.get()) {
            throw new IllegalStateException("Executor is stopped");
        }

        WrappedTask<T> job = new WrappedTask<>(task);

        this.buildQueue.add(job);

        synchronized (this.jobNotifier) {
            this.jobNotifier.notify();
        }

        return job.future;
    }

    /**
     * @return True if the build queue is empty
     */
    public boolean isBuildQueueEmpty() {
        return this.buildQueue.isEmpty();
    }

    /**
     * Initializes this chunk builder for the given world. If the builder is already running (which can happen during
     * a world teleportation event), the worker threads will first be stopped and all pending tasks will be discarded
     * before being started again.
     * @param world The world instance
     * @param renderPassManager The render pass manager used for the world
     */
    public void init(ClientWorld world, BlockRenderPassManager renderPassManager) {
        if (world == null) {
            throw new NullPointerException("World is null");
        }

        this.stopWorkers();

        this.world = world;
        this.renderPassManager = renderPassManager;
        this.sectionCache = new ClonedChunkSectionCache(this.world);

        this.startWorkers();
    }

    /**
     * Creates a rebuild task and defers it to the work queue. When the task is completed, it will be moved onto the
     * completed uploads queued which will then be drained during the next available synchronization point with the
     * main thread.
     * @param render The render to rebuild
     */
    public void deferRebuild(ChunkRenderContainer<T> render) {
        this.scheduleRebuildTaskAsync(render)
                .thenAccept(this::enqueueUpload);
    }


    /**
     * Enqueues the build task result to the pending result queue to be later processed during the next available
     * synchronization point on the main thread.
     * @param result The build task's result
     */
    private void enqueueUpload(ChunkBuildResult<T> result) {
        this.uploadQueue.add(result);
    }

    /**
     * Schedules the rebuild task asynchronously on the worker pool, returning a future wrapping the task.
     * @param render The render to rebuild
     */
    public CompletableFuture<ChunkBuildResult<T>> scheduleRebuildTaskAsync(ChunkRenderContainer<T> render) {
        return this.schedule(this.createRebuildTask(render));
    }

    /**
     * Creates a task to rebuild the geometry of a {@link ChunkRenderContainer}.
     * @param render The render to rebuild
     */
    private ChunkRenderBuildTask<T> createRebuildTask(ChunkRenderContainer<T> render) {
        render.cancelRebuildTask();

        ChunkRenderContext context = WorldSlice.prepare(this.world, render.getChunkPos(), this.sectionCache);

        if (context == null) {
            return new ChunkRenderEmptyBuildTask<>(render);
        } else {
            return new ChunkRenderRebuildTask<>(render, context, render.getRenderOrigin());
        }
    }

    public void onChunkDataChanged(int x, int y, int z) {
        this.sectionCache.invalidate(x, y, z);
    }

    private class WorkerRunnable implements Runnable {
        private final AtomicBoolean running = ChunkBuilder.this.running;

        // The re-useable build buffers used by this worker for building chunk meshes
        private final ChunkBuildBuffers bufferCache;

        // Making this thread-local provides a small boost to performance by avoiding the overhead in synchronizing
        // caches between different CPU cores
        private final ChunkRenderCacheLocal cache;

        public WorkerRunnable(ChunkBuildBuffers bufferCache, ChunkRenderCacheLocal cache) {
            this.bufferCache = bufferCache;
            this.cache = cache;
        }

        @Override
        public void run() {
            // Run until the chunk builder shuts down
            while (this.running.get()) {
                WrappedTask<T> job = this.getNextJob();

                // If the job is null or no longer valid, keep searching for a task
                if (job == null || job.isCancelled()) {
                    continue;
                }

                ChunkBuildResult<T> result;

                try {
                    // Perform the build task with this worker's local resources and obtain the result
                    result = job.task.performBuild(this.cache, this.bufferCache, job);
                } catch (Exception e) {
                    // Propagate any exception from chunk building
                    job.future.completeExceptionally(e);
                    continue;
                } finally {
                    job.task.releaseResources();
                }

                // The result can be null if the task is cancelled
                if (result != null) {
                    // Notify the future that the result is now available
                    job.future.complete(result);
                } else if (!job.isCancelled()) {
                    // If the job wasn't cancelled and no result was produced, we've hit a bug
                    job.future.completeExceptionally(new RuntimeException("No result was produced by the task"));
                }
            }
        }

        /**
         * Returns the next task which this worker can work on or blocks until one becomes available. If no tasks are
         * currently available, it will wait on {@link ChunkBuilder#jobNotifier} field until notified.
         */
        private WrappedTask<T> getNextJob() {
            WrappedTask<T> job = ChunkBuilder.this.buildQueue.poll();

            if (job == null) {
                synchronized (ChunkBuilder.this.jobNotifier) {
                    try {
                        // it is possible for notifyAll in stopWorkers to be called before this wait call, causing a deadlock.
                        // as running will be set to false before notifyAll is called, this check should suffice to avoid the thread
                        // locking after stopWorkers has been started
                        if (this.running.get()) {
                            ChunkBuilder.this.jobNotifier.wait();
                        }
                    } catch (InterruptedException ignored) {
                    }
                }
            }

            return job;
        }
    }

    private static class WrappedTask<T extends ChunkGraphicsState> implements CancellationSource {
        private final ChunkRenderBuildTask<T> task;
        private final CompletableFuture<ChunkBuildResult<T>> future;

        private WrappedTask(ChunkRenderBuildTask<T> task) {
            this.task = task;
            this.future = new CompletableFuture<>();
        }

        @Override
        public boolean isCancelled() {
            return this.future.isCancelled();
        }
    }
}
