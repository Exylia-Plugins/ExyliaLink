package net.exylia.link.platform;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The plugin's own background threads: the connection tick, HTTP calls, file writes and delays.
 * The same on every platform (no Bukkit, Folia or proxy scheduler is involved), so nothing here
 * blocks a server thread; work that must run on one goes through {@link Platform#global}.
 */
public final class Scheduler {

    private final ScheduledExecutorService executor;
    private final Consumer<Throwable> errors;

    public Scheduler(@NotNull Consumer<Throwable> errors) {
        AtomicInteger count = new AtomicInteger();
        this.executor = Executors.newScheduledThreadPool(2, task -> {
            Thread thread = new Thread(task, "ExyliaLink-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.errors = errors;
    }

    public void async(@NotNull Runnable task) {
        executor.execute(guarded(task));
    }

    public @NotNull ScheduledFuture<?> later(long millis, @NotNull Runnable task) {
        return executor.schedule(guarded(task), Math.max(0, millis), TimeUnit.MILLISECONDS);
    }

    public @NotNull ScheduledFuture<?> every(long delayMillis, long periodMillis, @NotNull Runnable task) {
        return executor.scheduleWithFixedDelay(guarded(task), delayMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable failure) {
                errors.accept(failure);
            }
        };
    }
}
