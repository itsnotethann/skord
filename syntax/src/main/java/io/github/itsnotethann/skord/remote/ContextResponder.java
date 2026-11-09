package io.github.itsnotethann.skord.remote;

import java.util.List;
import java.util.concurrent.CompletionStage;

import io.github.itsnotethann.skord.bridge.api.CommandContext;
import io.github.itsnotethann.skord.discord.Button;
import io.github.itsnotethann.skord.discord.MessageBody;
import io.github.itsnotethann.skord.discord.MessageComponent;
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
		return message(id, MessageBody.of(text));
	}

	private @Nullable MessageRef message(String id, MessageBody body) {
		if (id == null || id.isEmpty())
			return null;
		Ref channel = context.channel();
		try {
			long channelId = channel == null ? 0 : Long.parseLong(channel.id());
			return new MessageRef(Long.parseLong(id), channelId, body.text(), body.text(), null, body.embeds());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Override
	public CompletionStage<@Nullable MessageRef> reply(MessageBody body, boolean ephemeral, boolean wantMessage) {
		return context.reply(body, ephemeral).thenApply(id -> message(id, body));
	}

	@Override
	public CompletionStage<@Nullable MessageRef> editOriginal(MessageBody body, boolean wantMessage) {
		return context.edit(null, body).thenApply(id -> message(id, body));
	}

	@Override
	public CompletionStage<@Nullable MessageRef> editMessage(String messageId, MessageBody body) {
		return context.edit(messageId, body).thenApply(id -> message(id, body));
	}

	@Override
	public CompletionStage<@Nullable MessageRef> followUp(MessageBody body, boolean ephemeral, boolean wantMessage) {
		return context.followUp(body, ephemeral).thenApply(id -> message(id, body));
	}

	@Override
	public CompletionStage<@Nullable MessageRef> update(MessageBody body, boolean wantMessage) {
		return context.update(body).thenApply(id -> message(id, body));
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

	@Override
	public CompletionStage<@Nullable MessageRef> reply(String text, List<? extends MessageComponent> components, boolean ephemeral,
			boolean wantMessage) {
		return context.reply(text, ephemeral, components).thenApply(id -> message(id, text));
	}

	@Override
	public CompletionStage<@Nullable MessageRef> editOriginal(String text, List<? extends MessageComponent> components,
			boolean wantMessage) {
		return context.edit(null, text, components).thenApply(id -> message(id, text));
	}

	@Override
	public CompletionStage<@Nullable MessageRef> followUp(String text, List<? extends MessageComponent> components, boolean ephemeral,
			boolean wantMessage) {
		return context.followUp(text, ephemeral, components).thenApply(id -> message(id, text));
	}

	@Override
	public CompletionStage<?> deferUpdate() {
		return context.deferUpdate();
	}

	@Override
	public CompletionStage<@Nullable MessageRef> update(String text, @Nullable List<? extends MessageComponent> components,
			boolean wantMessage) {
		return context.update(text, components).thenApply(id -> message(id, text));
	}

	@Override
	public CompletionStage<?> showModal(io.github.itsnotethann.skord.discord.Modal modal) {
		return context.showModal(modal);
	}
}
