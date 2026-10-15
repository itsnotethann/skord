package io.github.itsnotethann.skord.remote;

import java.util.concurrent.CompletionStage;

import io.github.itsnotethann.skord.bridge.api.CommandContext;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.Responder;
import io.github.itsnotethann.skord.protocol.Ref;
import org.jetbrains.annotations.Nullable;

final class ContextResponder implements Responder {

	private final CommandContext context;

	ContextResponder(CommandContext context) {
		this.context = context;
	}

	private @Nullable MessageRef message(String id, String text) {
		if (id == null || id.isEmpty())
			return null;
		Ref channel = context.channel();
		try {
			long channelId = channel == null ? 0 : Long.parseLong(channel.id());
			return new MessageRef(Long.parseLong(id), channelId, text, text);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Override
	public CompletionStage<?> deferReply(boolean ephemeral) {
		return context.defer(ephemeral);
	}

	@Override
	public CompletionStage<@Nullable MessageRef> reply(String text, boolean ephemeral, boolean wantMessage) {
		return context.reply(text, ephemeral).thenApply(id -> message(id, text));
	}

	@Override
	public CompletionStage<@Nullable MessageRef> editOriginal(String text, boolean wantMessage) {
		return context.edit(null, text).thenApply(id -> message(id, text));
	}

	@Override
	public CompletionStage<@Nullable MessageRef> editMessage(String messageId, String text) {
		return context.edit(messageId, text).thenApply(id -> message(id, text));
	}

	@Override
	public CompletionStage<?> deleteOriginal() {
		return context.deleteOriginal();
	}

	@Override
	public CompletionStage<@Nullable MessageRef> followUp(String text, boolean ephemeral, boolean wantMessage) {
		return context.followUp(text, ephemeral).thenApply(id -> message(id, text));
	}
}
