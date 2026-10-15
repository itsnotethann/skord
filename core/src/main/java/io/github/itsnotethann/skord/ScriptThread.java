package io.github.itsnotethann.skord;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.skriptlang.skript.platform.PlatformScheduler;
import org.skriptlang.skript.platform.PlatformTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one thread all script code runs on. Skript assumes a single main thread, so skord gives it one
 * rather than auditing the engine for concurrency.
 * <p>
 * Ticks are a unit of time here, not a loop: a task due in {@code n} ticks runs {@code n × 50 ms} from now
 * and nothing runs in between, so an idle bot costs no CPU. {@code wait 1 tick} still means 50 ms.
 */
public final class ScriptThread implements PlatformScheduler {

	public static final long TICK_MILLIS = 50;

	private static final Logger LOGGER = LoggerFactory.getLogger("skord");

	private final AtomicInteger ids = new AtomicInteger();
	private volatile Thread thread;
	private final ScheduledExecutorService main = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread created = new Thread(runnable, "skord-script");
		thread = created;
		return created;
	});
	private final ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
		Thread created = new Thread(runnable, "skord-async");
		created.setDaemon(true);
		return created;
	});

	/** Runs {@code task} on the script thread as soon as it is free. */
	public void execute(Runnable task) {
		main.execute(() -> guarded(task));
	}

	@Override
	public PlatformTask sync(Runnable task, long delayTicks, long periodTicks) {
		return schedule(main, task, delayTicks, periodTicks);
	}

	@Override
	public PlatformTask async(Runnable task, long delayTicks, long periodTicks) {
		// The timer lives on the script thread's scheduler; only the work itself hops to the pool.
		return schedule(main, () -> pool.execute(() -> guarded(task)), delayTicks, periodTicks);
	}

	private Task schedule(ScheduledExecutorService executor, Runnable body, long delayTicks, long periodTicks) {
		Task task = new Task(ids.incrementAndGet(), body);
		long delay = Math.max(0, delayTicks) * TICK_MILLIS;
		task.future = periodTicks < 0
			? executor.schedule(task, delay, TimeUnit.MILLISECONDS)
			: executor.scheduleWithFixedDelay(task, delay, Math.max(1, periodTicks) * TICK_MILLIS, TimeUnit.MILLISECONDS);
		return task;
	}

	@Override
	public <T> Future<T> callSync(Callable<T> task) {
		CompletableFuture<T> future = new CompletableFuture<>();
		Runnable call = () -> {
			try {
				future.complete(task.call());
			} catch (Throwable throwable) {
				future.completeExceptionally(throwable);
			}
		};
		if (isPrimaryThread())
			call.run();
		else
			main.execute(call);
		return future;
	}

	@Override
	public boolean isPrimaryThread() {
		return Thread.currentThread() == thread;
	}

	public void shutdown() {
		main.shutdown();
		pool.shutdownNow();
	}

	public boolean awaitTermination(long millis) throws InterruptedException {
		return main.awaitTermination(millis, TimeUnit.MILLISECONDS);
	}

	// A periodic task that throws is silently cancelled by ScheduledExecutorService; log and carry on instead.
	private static void guarded(Runnable task) {
		try {
			task.run();
		} catch (Throwable throwable) {
			LOGGER.error("Uncaught error in a scheduled task", throwable);
		}
	}

	private static final class Task implements PlatformTask, Runnable {

		private final int id;
		private final Runnable body;
		private volatile ScheduledFuture<?> future;
		private volatile boolean running;

		Task(int id, Runnable body) {
			this.id = id;
			this.body = body;
		}

		@Override
		public void run() {
			running = true;
			try {
				guarded(body);
			} finally {
				running = false;
			}
		}

		@Override
		public int id() {
			return id;
		}

		@Override
		public boolean isQueued() {
			ScheduledFuture<?> current = future;
			return current != null && !current.isDone() && !running;
		}

		@Override
		public boolean isRunning() {
			return running;
		}

		@Override
		public void cancel() {
			ScheduledFuture<?> current = future;
			if (current != null)
				current.cancel(false);
		}
	}
}
