package io.github.itsnotethann.skord.event;

import java.util.HashMap;
import java.util.Map;

import io.github.itsnotethann.skord.interaction.Interaction;
import io.github.itsnotethann.skord.interaction.JdaResponder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.Nullable;

/**
 * Someone ran a slash command. Holds plain values rather than the JDA event, so testbot can build one without
 * a gateway.
 *
 * @param command the full name, such as {@code ticket close}
 * @param options argument values by name. A user argument holds the {@link Member} when there is one,
 *                otherwise the {@link User}.
 */
public final class SlashEvent extends InteractionEvent {

	private final String command;
	private final Map<String, Object> options;
	private final @Nullable User user;
	private final @Nullable Member member;
	private final @Nullable MessageChannel channel;
	private final @Nullable Guild guild;

	public SlashEvent(String command, Interaction interaction, Map<String, Object> options, @Nullable User user,
					  @Nullable Member member, @Nullable MessageChannel channel, @Nullable Guild guild) {
		super(interaction);
		this.command = command;
		this.options = options;
		this.user = user;
		this.member = member;
		this.channel = channel;
		this.guild = guild;
	}

	public static SlashEvent from(SlashCommandInteractionEvent jda) {
		Map<String, Object> options = new HashMap<>();
		for (OptionMapping option : jda.getOptions()) {
			Object value = switch (option.getType()) {
				case STRING -> option.getAsString();
				case INTEGER -> option.getAsLong();
				case NUMBER -> option.getAsDouble();
				case BOOLEAN -> option.getAsBoolean();
				case USER -> option.getAsMember() != null ? option.getAsMember() : option.getAsUser();
				case ROLE -> option.getAsRole();
				case CHANNEL -> option.getAsChannel() instanceof Category category
					? category
					: option.getAsChannel() instanceof MessageChannel channel ? channel : option.getAsChannel();
				case MENTIONABLE -> option.getAsMentionable();
				case ATTACHMENT -> option.getAsAttachment();
				default -> null;
			};
			if (value != null)
				options.put(option.getName(), value);
		}
		String name = jda.getFullCommandName();
		return new SlashEvent(name, new Interaction("/" + name, new JdaResponder(jda)), options, jda.getUser(),
			jda.getMember(), jda.getChannel() instanceof MessageChannel channel ? channel : null,
			jda.isFromGuild() ? jda.getGuild() : null);
	}

	public String command() {
		return command;
	}

	/** Every argument the user gave, by name. */
	public Map<String, Object> options() {
		return java.util.Collections.unmodifiableMap(options);
	}

	public @Nullable Object option(String name) {
		return options.get(name);
	}

	public @Nullable User user() {
		return user;
	}

	public @Nullable Member member() {
		return member;
	}

	public @Nullable MessageChannel channel() {
		return channel;
	}

	public @Nullable Guild guild() {
		return guild;
	}
}
