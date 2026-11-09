package io.github.itsnotethann.skord.jda;

import java.util.HashMap;
import java.util.Map;

import io.github.itsnotethann.skord.discord.CategoryRef;
import io.github.itsnotethann.skord.discord.ChannelRef;
import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MemberRef;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.RoleRef;
import io.github.itsnotethann.skord.discord.UserRef;
import io.github.itsnotethann.skord.event.ButtonEvent;
import io.github.itsnotethann.skord.event.SelectEvent;
import io.github.itsnotethann.skord.event.MessageEvent;
import io.github.itsnotethann.skord.event.SlashEvent;
import io.github.itsnotethann.skord.interaction.Interaction;
import io.github.itsnotethann.skord.interaction.JdaResponder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.Nullable;

public final class JdaRefs {

	private JdaRefs() {}

	public static UserRef user(User user) {
		return new UserRef(user.getIdLong(), user.getName());
	}

	public static MemberRef member(Member member) {
		return new MemberRef(member.getIdLong(), member.getGuild().getIdLong(), member.getEffectiveName());
	}

	public static GuildRef guild(Guild guild) {
		return new GuildRef(guild.getIdLong(), guild.getName());
	}

	public static ChannelRef channel(MessageChannel channel) {
		return new ChannelRef(channel.getIdLong(), channel.getName());
	}

	public static CategoryRef category(Category category) {
		return new CategoryRef(category.getIdLong(), category.getName());
	}

	public static RoleRef role(Role role) {
		return new RoleRef(role.getIdLong(), role.getName());
	}

	public static MessageRef message(Message message) {
		return new MessageRef(message.getIdLong(), message.getChannelIdLong(), message.getContentRaw(),
			message.getContentDisplay(), user(message.getAuthor()), JdaMessages.fromJda(message.getEmbeds()));
	}

	public static MessageEvent message(MessageReceivedEvent jda) {
		return new MessageEvent(message(jda.getMessage()), user(jda.getAuthor()), channel(jda.getChannel()),
			jda.isFromGuild() ? guild(jda.getGuild()) : null);
	}

	public static SlashEvent slash(SlashCommandInteractionEvent jda) {
		Map<String, Object> options = new HashMap<>();
		for (OptionMapping option : jda.getOptions()) {
			Object value = switch (option.getType()) {
				case STRING -> option.getAsString();
				case INTEGER -> option.getAsLong();
				case NUMBER -> option.getAsDouble();
				case BOOLEAN -> option.getAsBoolean();
				case USER -> option.getAsMember() != null ? member(option.getAsMember()) : user(option.getAsUser());
				case ROLE -> role(option.getAsRole());
				case CHANNEL -> channelOption(option);
				default -> null;
			};
			if (value != null)
				options.put(option.getName(), value);
		}
		String name = jda.getFullCommandName();
		return new SlashEvent(name, new Interaction("/" + name, new JdaResponder(jda)), options, user(jda.getUser()),
			jda.getMember() == null ? null : member(jda.getMember()),
			jda.getChannel() instanceof MessageChannel channel ? channel(channel) : null,
			jda.isFromGuild() ? guild(jda.getGuild()) : null);
	}

	public static ButtonEvent button(ButtonInteractionEvent jda) {
		String id = jda.getComponentId();
		return new ButtonEvent(id, new Interaction("button " + id, new JdaResponder(jda), true), message(jda.getMessage()),
			user(jda.getUser()), jda.getMember() == null ? null : member(jda.getMember()),
			jda.getChannel() instanceof MessageChannel channel ? channel(channel) : null,
			jda.isFromGuild() ? guild(jda.getGuild()) : null);
	}

	public static SelectEvent select(net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent jda) {
		String id = jda.getComponentId();
		return new SelectEvent(id, jda.getValues(), new Interaction("select menu " + id, new JdaResponder(jda), true),
			message(jda.getMessage()), user(jda.getUser()), jda.getMember() == null ? null : member(jda.getMember()),
			jda.getChannel() instanceof MessageChannel channel ? channel(channel) : null,
			jda.isFromGuild() ? guild(jda.getGuild()) : null);
	}

	/** A modal submit; its inputs by id, in the modal's order. An optional input left empty reads as empty text. */
	public static io.github.itsnotethann.skord.event.ModalEvent modal(
			net.dv8tion.jda.api.events.interaction.ModalInteractionEvent jda) {
		String id = jda.getModalId();
		java.util.Map<String, String> inputs = new java.util.LinkedHashMap<>();
		for (net.dv8tion.jda.api.interactions.modals.ModalMapping value : jda.getValues()) {
			if (value.getType() == net.dv8tion.jda.api.components.Component.Type.TEXT_INPUT)
				inputs.put(value.getCustomId(), value.getAsString());
		}
		Message from = jda.getMessage();
		return new io.github.itsnotethann.skord.event.ModalEvent(id, inputs,
			Interaction.modalSubmit("modal " + id, new JdaResponder(jda), from != null),
			from == null ? null : message(from), user(jda.getUser()),
			jda.getMember() == null ? null : member(jda.getMember()),
			jda.getChannel() instanceof MessageChannel channel ? channel(channel) : null,
			jda.isFromGuild() ? guild(jda.getGuild()) : null);
	}

	private static @Nullable Object channelOption(OptionMapping option) {
		if (option.getAsChannel() instanceof Category category)
			return category(category);
		if (option.getAsChannel() instanceof MessageChannel channel)
			return channel(channel);
		return null;
	}
}
