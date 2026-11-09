package io.github.itsnotethann.skord.discord;

import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * What a send, reply or edit puts in a message. For an edit, a null {@code embeds} or {@code components} leaves
 * that part of the message as it is and an empty list removes it; for a new message null and empty mean the same.
 *
 * @param text     the message's text; empty for none, which needs at least one embed
 * @param mentions whether the text's mentions ping. Off unless a script says {@code allowing mentions}: a reply
 *                 that quotes a user's text, or a {@code %user's mention%} in a welcome, then never pings anyone.
 */
public record MessageBody(String text, @Nullable List<Embed> embeds, @Nullable List<MessageComponent> components,
						  boolean mentions) {

	public MessageBody {
		text = text == null ? "" : text;
		embeds = embeds == null ? null : List.copyOf(embeds);
		components = components == null ? null : List.copyOf(components);
	}

	public static MessageBody of(String text) {
		return new MessageBody(text, null, null, false);
	}

	public static MessageBody of(String text, @Nullable List<? extends MessageComponent> components) {
		return new MessageBody(text, null, components == null ? null : List.copyOf(components), false);
	}

	public MessageBody withComponents(@Nullable List<? extends MessageComponent> value) {
		return new MessageBody(text, embeds, value == null ? null : List.copyOf(value), mentions);
	}

	public MessageBody withEmbeds(@Nullable List<Embed> value) {
		return new MessageBody(text, value, components, mentions);
	}

	public boolean hasEmbeds() {
		return embeds != null && !embeds.isEmpty();
	}

	public boolean hasComponents() {
		return components != null && !components.isEmpty();
	}

	/** Whether this can be sent as a new message: some text or at least one embed. */
	public boolean sendable() {
		return !text.isEmpty() || hasEmbeds();
	}

	/** Why Discord would refuse this, or null: one of the embed limits in {@link Embeds#problem}. */
	public @Nullable String problem() {
		return embeds == null ? null : Embeds.problem(embeds);
	}

	/**
	 * Fails with an {@code invalid input} error when an embed limit is broken, so the message is left out whole
	 * rather than sent half-built.
	 */
	public MessageBody checked() {
		String problem = problem();
		if (problem != null)
			throw new DiscordException(new DiscordError(DiscordError.Type.INVALID_INPUT, problem, null, 0));
		return this;
	}
}
