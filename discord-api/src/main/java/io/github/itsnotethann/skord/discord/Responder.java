package io.github.itsnotethann.skord.discord;

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
}
