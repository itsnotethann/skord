package io.github.itsnotethann.skord.syntax;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import io.github.itsnotethann.skord.discord.DiscordError;
import io.github.itsnotethann.skord.discord.DiscordException;
import io.github.itsnotethann.skord.discord.Embed;
import io.github.itsnotethann.skord.discord.MessageBody;
import io.github.itsnotethann.skord.discord.MessageComponent;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.PlatformEvent;

/**
 * The part of a send, reply or edit pattern that says what goes in the message: the text, then
 * {@code with embed[s] ...} and {@code with components ...} in either order, or embeds with no text. For edits,
 * {@code with no embeds} and {@code with no components} remove them. Each effect builds its patterns from these
 * pieces and reads them back with {@link #parse}.
 */
final class Outgoing {

	static final String EMBEDS = "(embed[s] %-discordembeds%|noembeds:no embeds)";
	static final String COMPONENTS = "(components %-discordcomponents%|nocomponents:no components)";
	/** After the text: embeds, then components, each optional. */
	static final String TAIL = "[with " + EMBEDS + "] [with " + COMPONENTS + "]";
	/** After the text: components, then embeds. */
	static final String SWAPPED = "with " + COMPONENTS + " with " + EMBEDS;
	/** In place of the text: a message with embeds and no text. */
	static final String EMBEDS_ONLY = "embed[s] %discordembeds% [with " + COMPONENTS + "]";
	static final String MENTIONS = "[mentions:allowing mentions]";

	/** Which of the three shapes a pattern used, which fixes the order of its expressions. */
	enum Form { TEXT, SWAPPED, EMBEDS }

	private final @Nullable Expression<String> text;
	private final @Nullable Expression<Embed> embeds;
	private final @Nullable Expression<MessageComponent> components;
	private final boolean noEmbeds;
	private final boolean noComponents;
	private final boolean mentions;
	private final int next;

	private Outgoing(@Nullable Expression<String> text, @Nullable Expression<Embed> embeds,
					 @Nullable Expression<MessageComponent> components, boolean noEmbeds, boolean noComponents,
					 boolean mentions, int next) {
		this.text = text;
		this.embeds = embeds;
		this.components = components;
		this.noEmbeds = noEmbeds;
		this.noComponents = noComponents;
		this.mentions = mentions;
		this.next = next;
	}

	/**
	 * Reads the message part starting at {@code exprs[at]}: text, embeds, components for {@link Form#TEXT}; text,
	 * components, embeds for {@link Form#SWAPPED}; embeds, components for {@link Form#EMBEDS}.
	 */
	@SuppressWarnings("unchecked")
	static Outgoing parse(Expression<?>[] exprs, int at, Form form, ParseResult result) {
		Expression<String> text = null;
		Expression<Embed> embeds;
		Expression<MessageComponent> components;
		switch (form) {
			case TEXT -> {
				text = (Expression<String>) exprs[at++];
				embeds = (Expression<Embed>) exprs[at++];
				components = (Expression<MessageComponent>) exprs[at++];
			}
			case SWAPPED -> {
				text = (Expression<String>) exprs[at++];
				components = (Expression<MessageComponent>) exprs[at++];
				embeds = (Expression<Embed>) exprs[at++];
			}
			default -> {
				embeds = (Expression<Embed>) exprs[at++];
				components = (Expression<MessageComponent>) exprs[at++];
			}
		}
		return new Outgoing(text, embeds, components, result.hasTag("noembeds"), result.hasTag("nocomponents"),
			result.hasTag("mentions"), at);
	}

	/** The index of the first expression after the message part. */
	int next() {
		return next;
	}

	/** For a new message there is nothing to remove: {@code with no embeds} there is a load error. */
	boolean refuseRemoval(String what) {
		if (noEmbeds || noComponents) {
			Skript.error("'with no " + (noEmbeds ? "embeds" : "components") + "' only makes sense when editing a message; "
				+ what + " makes a new one");
			return false;
		}
		return true;
	}

	boolean hasComponents() {
		return components != null;
	}

	/**
	 * The body to send, or null when there's nothing to send: an unset text, or no text and no embeds. Embeds and
	 * components that weren't mentioned are null, so an edit leaves them; {@code with no ...} makes them empty.
	 */
	@Nullable MessageBody body(PlatformEvent event) {
		String value = "";
		if (text != null) {
			value = text.getSingle(event);
			if (value == null)
				return null;
		}
		List<Embed> embedList = null;
		if (embeds != null)
			embedList = List.of(embeds.getArray(event));
		else if (noEmbeds)
			embedList = List.of();
		List<MessageComponent> componentList = null;
		if (components != null)
			componentList = List.of(components.getArray(event));
		else if (noComponents)
			componentList = List.of();
		MessageBody body = new MessageBody(value, embedList, componentList, mentions);
		if (value.isEmpty() && !body.hasEmbeds())
			return null;
		return body;
	}

	/** A failed request for a body that breaks one of Discord's limits, so the message is left out whole. */
	static <T> CompletionStage<T> refused(String problem) {
		return CompletableFuture.failedFuture(new DiscordException(
			new DiscordError(DiscordError.Type.INVALID_INPUT, problem, null, 0)));
	}

	String toString(@Nullable PlatformEvent event, boolean debug) {
		StringBuilder out = new StringBuilder(text == null ? "" : text.toString(event, debug));
		if (embeds != null)
			out.append(text == null ? "embeds " : " with embeds ").append(embeds.toString(event, debug));
		else if (noEmbeds)
			out.append(" with no embeds");
		if (components != null)
			out.append(" with components ").append(components.toString(event, debug));
		else if (noComponents)
			out.append(" with no components");
		if (mentions)
			out.append(" allowing mentions");
		return out.toString();
	}
}
