package io.github.itsnotethann.skord.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.itsnotethann.skord.discord.ChannelRef;
import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MemberRef;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.UserRef;
import io.github.itsnotethann.skord.interaction.Interaction;
import org.jetbrains.annotations.Nullable;

/**
 * Someone submitted a modal. {@link #inputs()} holds each text input's value by input id, in the modal's order.
 * {@link #message()} is the message whose component opened the modal, or null when a slash command did; only then
 * can {@code edit the message to} answer it.
 */
public final class ModalEvent extends InteractionEvent {

	private final String id;
	private final Map<String, String> inputs;
	private final @Nullable MessageRef message;
	private final @Nullable UserRef user;
	private final @Nullable MemberRef member;
	private final @Nullable ChannelRef channel;
	private final @Nullable GuildRef guild;

	public ModalEvent(String id, Map<String, String> inputs, Interaction interaction, @Nullable MessageRef message,
					  @Nullable UserRef user, @Nullable MemberRef member, @Nullable ChannelRef channel,
					  @Nullable GuildRef guild) {
		super(interaction);
		this.id = id;
		this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
		this.message = message;
		this.user = user;
		this.member = member;
		this.channel = channel;
		this.guild = guild;
	}

	public String id() {
		return id;
	}

	public Map<String, String> inputs() {
		return inputs;
	}

	public @Nullable MessageRef message() {
		return message;
	}

	public @Nullable UserRef user() {
		return user;
	}

	public @Nullable MemberRef member() {
		return member;
	}

	public @Nullable ChannelRef channel() {
		return channel;
	}

	public @Nullable GuildRef guild() {
		return guild;
	}
}
