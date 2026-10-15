package io.github.itsnotethann.skord.bridge.api;

import java.util.Map;
import java.util.concurrent.CompletionStage;

import io.github.itsnotethann.skord.protocol.Ref;
import org.jetbrains.annotations.Nullable;

/**
 * One use of a command, as the handler sees it: who ran it, with what, and the means to answer. Answers go back
 * to the bot, which applies them to the Discord interaction; each returns a stage that completes when Discord
 * accepted it.
 */
public interface CommandContext {

	/** The full command name, such as {@code ticket close}. */
	String command();

	/**
	 * Argument values by name: {@link String}, {@link Long}, {@link Double}, {@link Boolean}, or a {@link Ref}
	 * for a user, member, role, channel or category. Optional arguments left out are absent.
	 */
	Map<String, Object> options();

	default @Nullable Object option(String name) {
		return options().get(name);
	}

	@Nullable Ref user();

	@Nullable Ref member();

	@Nullable Ref guild();

	@Nullable Ref channel();

	/** The first answer. Completes with the new message's id. */
	CompletionStage<String> reply(String text, boolean ephemeral);

	/** Acknowledges now and answers later: Discord shows "thinking...". Answer with {@link #reply}. */
	CompletionStage<Void> defer(boolean ephemeral);

	/** Edits a message this interaction sent, or the original reply when {@code messageId} is null. */
	CompletionStage<String> edit(@Nullable String messageId, String text);

	/** A further message after the first answer. Completes with its id. */
	CompletionStage<String> followUp(String text, boolean ephemeral);
}
