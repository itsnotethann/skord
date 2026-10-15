package io.github.itsnotethann.skord.event;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.jetbrains.annotations.Nullable;

public final class MessageEvent extends SkordEvent {

	private final MessageReceivedEvent jda;

	public MessageEvent(MessageReceivedEvent jda) {
		this.jda = jda;
	}

	public Message message() {
		return jda.getMessage();
	}

	public User author() {
		return jda.getAuthor();
	}

	public MessageChannel channel() {
		return jda.getChannel();
	}

	public @Nullable Guild guild() {
		return jda.isFromGuild() ? jda.getGuild() : null;
	}
}
