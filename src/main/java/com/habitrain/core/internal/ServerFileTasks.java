package com.habitrain.core.internal;

import net.minecraft.server.MinecraftServer;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Bounded file work. Callers snapshot paths/data on the server thread before submitting. */
public final class ServerFileTasks {
    private static volatile MinecraftServer owner;
    private static ExecutorService executor;
    private ServerFileTasks() {}

    public static synchronized void start(MinecraftServer server) {
        if (executor != null && !executor.isTerminated()) throw new IllegalStateException("Previous file worker has not stopped");
        owner = server;
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16), runnable -> {
            Thread thread = new Thread(runnable, "HabiTrain-ServerFiles");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static boolean isCurrent(MinecraftServer server) {
        return server != null && owner == server && !Thread.currentThread().isInterrupted();
    }

    public static synchronized <T> boolean submit(MinecraftServer server, Supplier<T> work,
                                                   BiConsumer<T, Throwable> completion) {
        if (!isCurrent(server) || executor == null) return false;
        try {
            executor.execute(() -> {
                if (!isCurrent(server)) return;
                T value = null;
                Throwable failure = null;
                try { value = work.get(); } catch (Exception error) { failure = error; }
                T result = value;
                Throwable error = failure;
                if (isCurrent(server)) server.execute(() -> {
                    if (isCurrent(server)) completion.accept(result, error);
                });
            });
            return true;
        } catch (RejectedExecutionException busy) { return false; }
    }

    public static synchronized void shutdown() {
        owner = null;
        if (executor != null) executor.shutdownNow();
    }

    public static void awaitStopped() {
        ExecutorService current;
        synchronized (ServerFileTasks.class) { current = executor; }
        if (current == null) return;
        try {
            if (!current.awaitTermination(5, TimeUnit.SECONDS))
                LoggerFactory.getLogger(ServerFileTasks.class).warn("File worker is still stopping; old results are rejected");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
