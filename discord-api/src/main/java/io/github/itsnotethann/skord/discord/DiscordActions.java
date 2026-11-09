package io.github.itsnotethann.skord.discord;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface DiscordActions {

	CompletionStage<MessageRef> send(ChannelRef channel, String text);

	/** Sends a message with buttons under it. With no buttons it is the plain {@link #send}. */
	default CompletionStage<MessageRef> send(ChannelRef channel, String text, List<? extends MessageComponent> components) {
		if (components.isEmpty())
			return send(channel, text);
		throw new UnsupportedOperationException("this Discord cannot send components");
	}

	/**
	 * Sends a message with any of text, embeds and components. Mentions in the text ping only when the body allows
	 * them. Implementations from before embeds fall back to the text-and-components forms when there are none.
	 */
	default CompletionStage<MessageRef> send(ChannelRef channel, MessageBody body) {
		if (body.hasEmbeds())
			throw new UnsupportedOperationException("this Discord cannot send embeds");
		return body.components() == null ? send(channel, body.text()) : send(channel, body.text(), body.components());
	}

	CompletionStage<MessageRef> reply(MessageRef message, String text);

	/** Replies to a message with any of text, embeds and components. */
	default CompletionStage<MessageRef> reply(MessageRef message, MessageBody body) {
		if (body.hasEmbeds() || body.hasComponents())
			throw new UnsupportedOperationException("this Discord cannot reply with embeds or components");
		return reply(message, body.text());
	}

	CompletionStage<MessageRef> edit(MessageRef message, String text);

	/** Edits a message's text, and its embeds and components where the body sets them. */
	default CompletionStage<MessageRef> edit(MessageRef message, MessageBody body) {
		if (body.embeds() != null || body.components() != null)
			throw new UnsupportedOperationException("this Discord cannot edit embeds or components");
		return edit(message, body.text());
	}

	/** Deletes a message. An unknown one fails with {@code unknown message}. */
	default CompletionStage<Void> delete(MessageRef message) {
		return CompletableFuture.failedFuture(new UnsupportedOperationException("this Discord cannot delete messages"));
	}

	/** Fetches a message by id, with its text, author and embeds. An unknown one fails with {@code unknown message}. */
	default CompletionStage<MessageRef> fetchMessage(ChannelRef channel, long id) {
		return CompletableFuture.failedFuture(new UnsupportedOperationException("this Discord cannot fetch messages"));
	}

	CompletionStage<ChannelRef> createTextChannel(CategoryRef category, String name);

	CompletionStage<ChannelRef> createTextChannel(GuildRef guild, String name);

	CompletionStage<Object> fetch(DiscordRef ref, FetchProperty property);

	CompletionStage<Long> gatewayPing();
}
