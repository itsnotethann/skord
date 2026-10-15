package io.github.itsnotethann.skord.discord;

import java.util.concurrent.CompletionStage;

public interface DiscordActions {

	CompletionStage<MessageRef> send(ChannelRef channel, String text);

	CompletionStage<MessageRef> reply(MessageRef message, String text);

	CompletionStage<MessageRef> edit(MessageRef message, String text);

	CompletionStage<ChannelRef> createTextChannel(CategoryRef category, String name);

	CompletionStage<ChannelRef> createTextChannel(GuildRef guild, String name);

	CompletionStage<Object> fetch(DiscordRef ref, FetchProperty property);

	CompletionStage<Long> gatewayPing();
}
