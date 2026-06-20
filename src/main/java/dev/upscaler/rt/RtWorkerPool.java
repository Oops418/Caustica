package dev.upscaler.rt;

import dev.upscaler.UpscalerMod;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared daemon thread pool for CPU-heavy RT work that must stay off the render thread — terrain
 * tessellation today (see {@link RtTerrain}), LOD build / atlas stitching later. Jobs submitted here
 * must be pure CPU: <b>no Vulkan calls and no shared mutable state</b> (the graphics queue is owned by
 * the render thread, and queue submission stays single-threaded). Results are collected back on the
 * render thread via the returned {@link Future}.
 *
 * <p>Sized at {@code -Dupscaler.rt.workerThreads} (default {@code clamp(cores/2, 1, 4)}) to leave cores
 * for Minecraft's own chunk meshers. Core threads time out when idle so the pool costs nothing between
 * bursts; threads are daemon so they never block JVM exit.
 */
public final class RtWorkerPool {
    public static final RtWorkerPool INSTANCE = new RtWorkerPool();

    private static final int THREADS = resolveThreads();

    private ThreadPoolExecutor exec;

    private RtWorkerPool() {
    }

    private static int resolveThreads() {
        int def = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
        return Math.max(1, Integer.getInteger("upscaler.rt.workerThreads", def));
    }

    private synchronized ThreadPoolExecutor executor() {
        if (exec == null) {
            ThreadFactory factory = new ThreadFactory() {
                private final AtomicInteger n = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "rt-worker-" + n.incrementAndGet());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1); // yield to the render + MC mesher threads
                    return t;
                }
            };
            ThreadPoolExecutor e = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(), factory);
            e.allowCoreThreadTimeOut(true);
            exec = e;
            UpscalerMod.LOGGER.info("RT worker pool started with {} thread(s)", THREADS);
        }
        return exec;
    }

    /** Submit a pure-CPU job; poll the returned future on the render thread. */
    public <T> Future<T> submit(Callable<T> job) {
        return executor().submit(job);
    }

    /** Stop all workers and drop queued jobs. Safe to call when never started. */
    public synchronized void shutdown() {
        if (exec != null) {
            exec.shutdownNow();
            exec = null;
        }
    }
}
