package io.github.itsnotethann.skord.discord;

import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * A message. What it holds besides its id and channel is what was known when it was made: a sent or fetched message
 * knows its text, author and embeds; one rebuilt from an id knows nothing more.
 *
 * @param author null when not known
 * @param embeds null when not known
 */
public record MessageRef(long id, long channel, @Nullable String content, @Nullable String display,
						 @Nullable UserRef author, @Nullable List<Embed> embeds) implements DiscordRef {

	public MessageRef {
		embeds = embeds == null ? null : List.copyOf(embeds);
	}

	public MessageRef(long id, long channel, @Nullable String content, @Nullable String display) {
		this(id, channel, content, display, null, null);
	}

	public MessageRef(long id, long channel) {
		this(id, channel, null, null);
	}

	/** The same message as {@code body} left it: its text, and its embeds when the body set them. */
	public MessageRef after(MessageBody body) {
		return new MessageRef(id, channel, body.text(), body.text(), author, body.embeds() != null ? body.embeds() : embeds);
	}

	@Override
	public @Nullable String name() {
		return display;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof MessageRef ref && ref.id == id;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(id);
	}
}
