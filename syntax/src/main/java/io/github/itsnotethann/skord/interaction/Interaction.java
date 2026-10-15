package io.github.itsnotethann.skord.interaction;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ch.njol.skript.Skript;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.Responder;
import org.jetbrains.annotations.Nullable;

/**
 * One interaction and its acknowledgement. Discord fails an interaction that isn't acknowledged within three
 * seconds; this class makes sure that never happens to a script that does reply.
 * <p>
 * The state moves {@code NONE -> DEFERRED -> REPLIED} or {@code NONE -> REPLIED}, always by compare-and-set,
 * because two threads race for it: the script thread replying, and the watchdog deferring at
 * {@link #WATCHDOG_MILLIS}. Whoever wins the CAS sends the acknowledgement; the loser adapts. A reply that finds
 * the interaction deferred edits the "thinking..." message instead, and a reply that finds it already answered
 * becomes a follow-up. Both wait for the acknowledgement to land first, because Discord rejects an edit or
 * follow-up that arrives before it.
 */
public final class Interaction {

	public enum Ack { NONE, DEFERRED, REPLIED }

	/** When the watchdog defers, measured from receiving the interaction. Discord's limit is 3 s. */
	public static final long WATCHDOG_MILLIS = Long.getLong("skord.ack-watchdog-ms", 2500);

	private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "skord-ack-watchdog");
		thread.setDaemon(true);
		return thread;
	});

	private final String name;
	private final Responder responder;
	private final AtomicReference<Ack> ack = new AtomicReference<>(Ack.NONE);
	// Completes when the acknowledgement (reply or deferral) lands. Edits and follow-ups chain on it.
	private final CompletableFuture<Object> acknowledged = new CompletableFuture<>();
	private final ScheduledFuture<?> watchdog;
	private volatile boolean deferredEphemeral;
	private volatile boolean abandoned;

	/** @param name what logs call this interaction, such as {@code /ticket close} */
	public Interaction(String name, Responder responder) {
		this.name = name;
		this.responder = responder;
		this.watchdog = WATCHDOG.schedule(() -> {
			if (!abandoned)
				defer(false);
		}, WATCHDOG_MILLIS, TimeUnit.MILLISECONDS);
	}

	public String name() {
		return name;
	}

	public Ack ack() {
		return ack.get();
	}

	/** Defers if nothing has acknowledged the interaction yet; otherwise does nothing. */
	public CompletionStage<?> defer(boolean ephemeral) {
		if (!ack.compareAndSet(Ack.NONE, Ack.DEFERRED))
			return acknowledged;
		watchdog.cancel(false);
		deferredEphemeral = ephemeral;
		return pipe(responder.deferReply(ephemeral));
	}

	public CompletionStage<@Nullable MessageRef> reply(String text, boolean ephemeral, boolean wantMessage) {
		if (ack.compareAndSet(Ack.NONE, Ack.REPLIED)) {
			watchdog.cancel(false);
			return pipe(responder.reply(text, ephemeral, wantMessage));
		}
		if (ack.compareAndSet(Ack.DEFERRED, Ack.REPLIED)) {
			// An ephemeral reply can't come from editing a public "thinking..." message: remove it and send
			// the reply as an ephemeral follow-up instead.
			if (ephemeral && !deferredEphemeral)
				return acknowledged
					.thenCompose(done -> responder.deleteOriginal())
					.thenCompose(done -> responder.followUp(text, true, wantMessage));
			return acknowledged.thenCompose(done -> responder.editOriginal(text, wantMessage));
		}
		return acknowledged.thenCompose(done -> responder.followUp(text, ephemeral, wantMessage));
	}

	/** Replaces the original response. Before any response exists, this is the response. */
	public CompletionStage<@Nullable MessageRef> editOriginal(String text) {
		if (ack.get() == Ack.NONE)
			return reply(text, false, true);
		return acknowledged.thenCompose(done -> responder.editOriginal(text, true));
	}

	/** Removes the original response. */
	public CompletionStage<?> deleteOriginal() {
		return acknowledged.thenCompose(done -> responder.deleteOriginal());
	}

	/** Edits another message this interaction sent, by id. */
	public CompletionStage<@Nullable MessageRef> editMessage(String messageId, String text) {
		return acknowledged.thenCompose(done -> responder.editMessage(messageId, text));
	}

	/**
	 * Called once every trigger for the interaction has finished its synchronous walk. If none acknowledged it:
	 * a trigger that paused will reply later, so defer now; if none paused, nothing will reply, so say so
	 * rather than leave a "thinking..." that never resolves.
	 */
	public void afterDispatch(boolean paused) {
		if (ack.get() != Ack.NONE)
			return;
		if (paused) {
			defer(false);
			return;
		}
		abandoned = true;
		watchdog.cancel(false);
		Skript.warning(name + " finished without replying, so Discord will show it as failed. Reply with "
			+ "'reply with', or 'defer reply' if the answer comes from somewhere else.");
	}

	private <T> CompletionStage<T> pipe(CompletionStage<T> request) {
		request.whenComplete((result, error) -> {
			if (error != null)
				acknowledged.completeExceptionally(error);
			else
				acknowledged.complete(result == null ? Boolean.TRUE : result);
		});
		return request;
	}
}
