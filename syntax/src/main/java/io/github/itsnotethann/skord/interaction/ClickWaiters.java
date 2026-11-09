package io.github.itsnotethann.skord.interaction;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import io.github.itsnotethann.skord.event.ButtonEvent;
import io.github.itsnotethann.skord.event.ComponentEvent;
import io.github.itsnotethann.skord.event.SelectEvent;
import org.jetbrains.annotations.Nullable;

/**
 * One-shot listeners for {@code wait for a button click} and {@code wait for a selection}: each waits for one
 * use of one kind of component on one message, optionally by one user. A click completes with the component's
 * id, a selection with the picked values; both complete with null when the time runs out. Every place that fires
 * a {@link ComponentEvent} offers it here first; the event still runs its handlers.
 */
public final class ClickWaiters {

	private record Waiter(long message, long user, boolean selection, CompletableFuture<@Nullable Object> result) {}

	private static final List<Waiter> WAITERS = new CopyOnWriteArrayList<>();

	private ClickWaiters() {}

	/** Waits for a button click. @param user the user whose click counts, or 0 for anyone */
	public static CompletionStage<@Nullable Object> await(long message, long user, long timeoutMillis) {
		return await(message, user, false, timeoutMillis);
	}

	/** Waits for a selection on a select menu; completes with the picked values. */
	public static CompletionStage<@Nullable Object> awaitSelection(long message, long user, long timeoutMillis) {
		return await(message, user, true, timeoutMillis);
	}

	private static CompletionStage<@Nullable Object> await(long message, long user, boolean selection,
			long timeoutMillis) {
		CompletableFuture<@Nullable Object> result = new CompletableFuture<>();
		Waiter waiter = new Waiter(message, user, selection, result);
		WAITERS.add(waiter);
		result.completeOnTimeout(null, timeoutMillis, TimeUnit.MILLISECONDS)
			.whenComplete((id, error) -> WAITERS.remove(waiter));
		return result;
	}

	public static void offer(ComponentEvent used) {
		if (WAITERS.isEmpty() || used.message() == null)
			return;
		long message = used.message().id();
		long user = used.user() == null ? 0 : used.user().id();
		boolean selection = used instanceof SelectEvent;
		Object answer = switch (used) {
			case SelectEvent select -> select.values();
			case ButtonEvent click -> click.id();
		};
		for (Waiter waiter : WAITERS) {
			if (waiter.selection() == selection && waiter.message() == message
				&& (waiter.user() == 0 || waiter.user() == user))
				waiter.result().complete(answer);
		}
	}

	public static int waiting() {
		return WAITERS.size();
	}
}
