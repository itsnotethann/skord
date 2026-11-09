package io.github.itsnotethann.skord.bridge.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import io.github.itsnotethann.skord.discord.MessageBody;
import io.github.itsnotethann.skord.discord.MessageComponent;
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

	/** Removes the original reply, such as a public deferral an ephemeral answer replaces. */
	CompletionStage<Void> deleteOriginal();

	/** For a component click: the component's id. Null for a command. */
	default @Nullable String component() {
		return null;
	}

	/** For a component click: the id of the message the component is on. Null for a command. */
	default @Nullable String message() {
		return null;
	}

	/**
	 * For a select menu: the values picked, possibly none. Null for a button click or a command, which is how a
	 * selection is told apart from a click.
	 */
	default @Nullable List<String> values() {
		return null;
	}

	/** {@link #reply(String, boolean)} with components (buttons, select menus) under the message. */
	default CompletionStage<String> reply(String text, boolean ephemeral, List<? extends MessageComponent> components) {
		throw new UnsupportedOperationException("this context cannot send components");
	}

	/** {@link #edit(String, String)} that also replaces the components; an empty list removes them. */
	default CompletionStage<String> edit(@Nullable String messageId, String text, List<? extends MessageComponent> components) {
		throw new UnsupportedOperationException("this context cannot send components");
	}

	/** {@link #followUp(String, boolean)} with components (buttons, select menus) under the message. */
	default CompletionStage<String> followUp(String text, boolean ephemeral, List<? extends MessageComponent> components) {
		throw new UnsupportedOperationException("this context cannot send components");
	}

	/**
	 * Answers a component click by replacing the message the component is on. A null list leaves its components, an
	 * empty one removes them.
	 */
	default CompletionStage<String> update(String text, @Nullable List<? extends MessageComponent> components) {
		throw new UnsupportedOperationException("only a component click can be updated");
	}

	// Bodies carry text, embeds, components and whether mentions ping. A context from before embeds falls back to
	// the forms above when the body has no embeds.

	/** The first answer, from a body (text, embeds, components). Completes with the new message's id. */
	default CompletionStage<String> reply(MessageBody body, boolean ephemeral) {
		noEmbeds(body);
		return body.components() == null ? reply(body.text(), ephemeral) : reply(body.text(), ephemeral, body.components());
	}

	/**
	 * Edits a message this interaction sent, or the original reply when {@code messageId} is null: its text, and its
	 * embeds and components where the body sets them (null leaves them, empty removes them).
	 */
	default CompletionStage<String> edit(@Nullable String messageId, MessageBody body) {
		noEmbeds(body);
		return body.components() == null ? edit(messageId, body.text()) : edit(messageId, body.text(), body.components());
	}

	/** A further message after the first answer, from a body. */
	default CompletionStage<String> followUp(MessageBody body, boolean ephemeral) {
		noEmbeds(body);
		return body.components() == null ? followUp(body.text(), ephemeral)
			: followUp(body.text(), ephemeral, body.components());
	}

	/** Answers a component click by replacing the message it is on, embeds and components included where set. */
	default CompletionStage<String> update(MessageBody body) {
		noEmbeds(body);
		return update(body.text(), body.components());
	}

	private static void noEmbeds(MessageBody body) {
		if (body.embeds() != null)
			throw new UnsupportedOperationException("this context cannot send embeds");
	}

	/** Acknowledges a component click without changing anything. */
	default CompletionStage<Void> deferUpdate() {
		throw new UnsupportedOperationException("only a component click can be updated");
	}

	/**
	 * Answers a command, click or selection by opening a modal. It must be the first answer: after a reply or a
	 * deferral the stage fails. The submit comes back to this server, to {@code Bridge.onModal}.
	 */
	default CompletionStage<Void> showModal(io.github.itsnotethann.skord.discord.Modal modal) {
		throw new UnsupportedOperationException("this context cannot show modals");
	}

	/** For a modal submit: the modal's id. Null otherwise. */
	default @Nullable String modal() {
		return null;
	}

	/**
	 * For a modal submit: each text input's value by input id, in the modal's order. Null for anything else.
	 * {@link #message()} is the message whose component opened the modal, or null when a command did.
	 */
	default @Nullable Map<String, String> inputs() {
		return null;
	}
}
