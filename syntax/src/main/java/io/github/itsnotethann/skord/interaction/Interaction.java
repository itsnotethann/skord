package io.github.itsnotethann.skord.interaction;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ch.njol.skript.Skript;
import io.github.itsnotethann.skord.discord.Button;
import io.github.itsnotethann.skord.discord.DiscordError;
import io.github.itsnotethann.skord.discord.DiscordException;
import io.github.itsnotethann.skord.discord.MessageComponent;
import io.github.itsnotethann.skord.discord.MessageBody;
import io.github.itsnotethann.skord.discord.Modal;
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
	private final boolean component;
	// A modal submit: answered silently when nothing answers it, and never with another modal.
	private final boolean submit;
	private volatile boolean deferredEphemeral;
	private volatile boolean updateDeferred;
	private volatile boolean abandoned;

	/** @param name what logs call this interaction, such as {@code /ticket close} */
	public Interaction(String name, Responder responder) {
		this(name, responder, false);
	}

	public Interaction(String name, Responder responder, boolean component) {
		this(name, responder, component, false);
	}

	/**
	 * A modal submit. {@code fromMessage} is whether the modal was opened from a component on a message, which is
	 * then the message {@link #update} edits.
	 */
	public static Interaction modalSubmit(String name, Responder responder, boolean fromMessage) {
		return new Interaction(name, responder, fromMessage, true);
	}

	private Interaction(String name, Responder responder, boolean component, boolean submit) {
		this.name = name;
		this.responder = responder;
		this.component = component;
		this.submit = submit;
		this.watchdog = WATCHDOG.schedule(() -> {
			if (!abandoned)
				settle();
		}, WATCHDOG_MILLIS, TimeUnit.MILLISECONDS);
	}

	private CompletionStage<?> settle() {
		return component ? deferUpdate() : defer(false);
	}

	private CompletionStage<?> deferUpdate() {
		if (!ack.compareAndSet(Ack.NONE, Ack.DEFERRED))
			return acknowledged;
		watchdog.cancel(false);
		updateDeferred = true;
		return pipe(responder.deferUpdate());
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
		return reply(MessageBody.of(text), ephemeral, wantMessage);
	}

	public CompletionStage<@Nullable MessageRef> reply(String text, @Nullable List<? extends MessageComponent> components,
			boolean ephemeral, boolean wantMessage) {
		return reply(MessageBody.of(text, components), ephemeral, wantMessage);
	}

	/**
	 * Answers with a body (text, embeds, components): the first reply, an edit of the "thinking..." left by a
	 * deferral, or a follow-up after the first answer. A body that breaks an embed limit fails without sending.
	 */
	public CompletionStage<@Nullable MessageRef> reply(MessageBody body, boolean ephemeral, boolean wantMessage) {
		String problem = body.problem();
		if (problem != null)
			return refused(problem);
		if (!body.hasComponents())
			return replyPlain(body.withComponents(null), ephemeral, wantMessage);
		if (ack.compareAndSet(Ack.NONE, Ack.REPLIED)) {
			watchdog.cancel(false);
			return pipe(responder.reply(body, ephemeral, wantMessage));
		}
		if (ack.compareAndSet(Ack.DEFERRED, Ack.REPLIED)) {
			if (updateDeferred || (ephemeral && !deferredEphemeral))
				return acknowledged.thenCompose(done -> responder.followUp(body, ephemeral, wantMessage));
			return acknowledged.thenCompose(done -> responder.editOriginal(body, wantMessage));
		}
		return acknowledged.thenCompose(done -> responder.followUp(body, ephemeral, wantMessage));
	}

	/** Answers a component click by replacing the message it is on. A null list leaves its components. */
	public CompletionStage<@Nullable MessageRef> update(String text, @Nullable List<? extends MessageComponent> components) {
		return update(MessageBody.of(text, components));
	}

	/**
	 * Answers a component click by replacing the message it is on: its text, and its embeds and components where
	 * the body sets them (null leaves them, empty removes them).
	 */
	public CompletionStage<@Nullable MessageRef> update(MessageBody body) {
		if (submit && !component)
			return refused(name + " is the submit of a modal a slash command opened, so it has no message to edit; "
				+ "answer it with 'reply with'");
		String problem = body.problem();
		if (problem != null)
			return refused(problem);
		if (ack.compareAndSet(Ack.NONE, Ack.REPLIED)) {
			watchdog.cancel(false);
			return pipe(responder.update(body, true));
		}
		ack.compareAndSet(Ack.DEFERRED, Ack.REPLIED);
		return acknowledged.thenCompose(done -> responder.editOriginal(body, true));
	}

	private CompletionStage<@Nullable MessageRef> replyPlain(MessageBody body, boolean ephemeral, boolean wantMessage) {
		if (ack.compareAndSet(Ack.NONE, Ack.REPLIED)) {
			watchdog.cancel(false);
			return pipe(responder.reply(body, ephemeral, wantMessage));
		}
		if (ack.compareAndSet(Ack.DEFERRED, Ack.REPLIED)) {
			// An ephemeral reply can't come from editing a public "thinking..." message: remove it and send
			// the reply as an ephemeral follow-up instead.
			if (updateDeferred)
				return acknowledged.thenCompose(done -> responder.followUp(body, ephemeral, wantMessage));
			if (ephemeral && !deferredEphemeral)
				return acknowledged
					.thenCompose(done -> responder.deleteOriginal())
					.thenCompose(done -> responder.followUp(body, true, wantMessage));
			return acknowledged.thenCompose(done -> responder.editOriginal(body, wantMessage));
		}
		return acknowledged.thenCompose(done -> responder.followUp(body, ephemeral, wantMessage));
	}

	/** Replaces the original response. Before any response exists, this is the response. */
	public CompletionStage<@Nullable MessageRef> editOriginal(String text) {
		return editOriginal(MessageBody.of(text));
	}

	/** {@link #editOriginal(String)} that also replaces the buttons; an empty list removes them. */
	public CompletionStage<@Nullable MessageRef> editOriginal(String text, List<? extends MessageComponent> components) {
		return editOriginal(MessageBody.of(text, components));
	}

	/** Replaces the original response's text, and its embeds and components where the body sets them. */
	public CompletionStage<@Nullable MessageRef> editOriginal(MessageBody body) {
		String problem = body.problem();
		if (problem != null)
			return refused(problem);
		if (ack.get() == Ack.NONE)
			return reply(body, false, true);
		return acknowledged.thenCompose(done -> responder.editOriginal(body, true));
	}

	/**
	 * Answers by opening a modal. It has to be the first response: Discord can't open a modal for an interaction
	 * that was already deferred or answered, so that fails with a clear error instead of being sent. Showing a modal
	 * counts as the answer, so nothing defers the interaction afterwards.
	 */
	public CompletionStage<?> showModal(Modal modal) {
		if (submit)
			return refused(name + " is a modal submit, and a modal submit can't be answered with another modal");
		Ack before = ack.get();
		if (before != Ack.NONE || !ack.compareAndSet(Ack.NONE, Ack.REPLIED))
			return refused("Can't show modal '" + modal.id() + "': " + name + " was already "
				+ ((before == Ack.NONE ? ack.get() : before) == Ack.DEFERRED ? "deferred" : "answered")
				+ ", and a modal must be an interaction's first response. Show it before any reply or wait.");
		watchdog.cancel(false);
		return pipe(responder.showModal(modal));
	}

	private static <T> CompletionStage<T> refused(String message) {
		return CompletableFuture.failedFuture(new DiscordException(
			new DiscordError(DiscordError.Type.INVALID_INPUT, message, null, 0)));
	}

	public boolean isModalSubmit() {
		return submit;
	}

	/** Acknowledges a component click now without changing the message, if nothing acknowledged it yet. */
	public CompletionStage<?> deferUpdateNow() {
		if (!component)
			throw new UnsupportedOperationException("only a component click can be updated");
		return deferUpdate();
	}

	public boolean isComponent() {
		return component;
	}

	/** Removes the original response. */
	public CompletionStage<?> deleteOriginal() {
		return acknowledged.thenCompose(done -> responder.deleteOriginal());
	}

	/** Edits another message this interaction sent, by id. */
	public CompletionStage<@Nullable MessageRef> editMessage(String messageId, String text) {
		return acknowledged.thenCompose(done -> responder.editMessage(messageId, text));
	}

	/** Edits another message this interaction sent, by id: its text, and its embeds and components where set. */
	public CompletionStage<@Nullable MessageRef> editMessage(String messageId, MessageBody body) {
		String problem = body.problem();
		if (problem != null)
			return refused(problem);
		return acknowledged.thenCompose(done -> responder.editMessage(messageId, body));
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
			settle();
			return;
		}
		abandoned = true;
		watchdog.cancel(false);
		if (component) {
			deferUpdate();
			return;
		}
		if (submit) {
			// A modal a slash command opened has no message to defer an edit of. An ephemeral deferral, removed at
			// once, closes the modal without leaving anything in the channel.
			defer(true);
			acknowledged.thenCompose(done -> responder.deleteOriginal());
			return;
		}
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
