package io.github.itsnotethann.skord.discord;

import java.util.List;
import java.util.concurrent.CompletionStage;

import org.jetbrains.annotations.Nullable;

/**
 * The requests that answer one interaction. {@code Interaction} decides which to send and when; this only
 * sends them. The bot's is backed by JDA; testbot has a fake that records them.
 * <p>
 * {@code wantMessage} says whether the caller stores the resulting message. Without it, an implementation may
 * complete with null rather than spend a request fetching the message.
 */
public interface Responder {

	CompletionStage<?> deferReply(boolean ephemeral);

	/** The initial response. */
	CompletionStage<@Nullable MessageRef> reply(String text, boolean ephemeral, boolean wantMessage);

	/** Replaces the original response, e.g. the "thinking..." left by a deferral. */
	CompletionStage<@Nullable MessageRef> editOriginal(String text, boolean wantMessage);

	/** Edits one message this interaction sent, by id. */
	CompletionStage<@Nullable MessageRef> editMessage(String messageId, String text);

	CompletionStage<?> deleteOriginal();

	/** A further message after the interaction has been answered. */
	CompletionStage<@Nullable MessageRef> followUp(String text, boolean ephemeral, boolean wantMessage);

	default CompletionStage<@Nullable MessageRef> reply(String text, List<? extends MessageComponent> components, boolean ephemeral,
			boolean wantMessage) {
		throw new UnsupportedOperationException("this responder cannot send components");
	}

	default CompletionStage<@Nullable MessageRef> editOriginal(String text, List<? extends MessageComponent> components,
			boolean wantMessage) {
		throw new UnsupportedOperationException("this responder cannot send components");
	}

	default CompletionStage<@Nullable MessageRef> followUp(String text, List<? extends MessageComponent> components, boolean ephemeral,
			boolean wantMessage) {
		throw new UnsupportedOperationException("this responder cannot send components");
	}

	/** Acknowledges a component click without a visible response. */
	default CompletionStage<?> deferUpdate() {
		throw new UnsupportedOperationException("only a component click can be updated");
	}

	/** Answers a component click by replacing the message the component is on. A null list leaves its components. */
	default CompletionStage<@Nullable MessageRef> update(String text, @Nullable List<? extends MessageComponent> components,
			boolean wantMessage) {
		throw new UnsupportedOperationException("only a component click can be updated");
	}

	// Bodies: text, embeds, components and mentions together. Responders from before embeds fall back to the forms
	// above when the body has no embeds; such a responder can't stop mentions from pinging.

	/** The initial response, from a body. */
	default CompletionStage<@Nullable MessageRef> reply(MessageBody body, boolean ephemeral, boolean wantMessage) {
		noEmbeds(body);
		return body.components() == null ? reply(body.text(), ephemeral, wantMessage)
			: reply(body.text(), body.components(), ephemeral, wantMessage);
	}

	/** Replaces the original response's text, and its embeds and components where the body sets them. */
	default CompletionStage<@Nullable MessageRef> editOriginal(MessageBody body, boolean wantMessage) {
		noEmbeds(body);
		return body.components() == null ? editOriginal(body.text(), wantMessage)
			: editOriginal(body.text(), body.components(), wantMessage);
	}

	/** A further message, from a body. */
	default CompletionStage<@Nullable MessageRef> followUp(MessageBody body, boolean ephemeral, boolean wantMessage) {
		noEmbeds(body);
		return body.components() == null ? followUp(body.text(), ephemeral, wantMessage)
			: followUp(body.text(), body.components(), ephemeral, wantMessage);
	}

	/** Replaces the message a component is on, setting its embeds and components where the body sets them. */
	default CompletionStage<@Nullable MessageRef> update(MessageBody body, boolean wantMessage) {
		noEmbeds(body);
		return update(body.text(), body.components(), wantMessage);
	}

	/** Edits one message this interaction sent, by id, from a body. */
	default CompletionStage<@Nullable MessageRef> editMessage(String messageId, MessageBody body) {
		if (body.embeds() != null || body.components() != null)
			throw new UnsupportedOperationException("this responder cannot edit embeds or components by id");
		return editMessage(messageId, body.text());
	}

	private static void noEmbeds(MessageBody body) {
		if (body.embeds() != null)
			throw new UnsupportedOperationException("this responder cannot send embeds");
	}

	/**
	 * Answers the interaction by opening a modal. Only a slash command, button click or selection can, and only as
	 * its first response; {@code Interaction} checks both before calling this.
	 */
	default CompletionStage<?> showModal(Modal modal) {
		throw new UnsupportedOperationException("this responder cannot show modals");
	}
}
