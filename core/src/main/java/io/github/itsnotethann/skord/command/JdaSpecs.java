package io.github.itsnotethann.skord.command;

import java.util.EnumSet;

import io.github.itsnotethann.skord.protocol.ArgKind;
import io.github.itsnotethann.skord.protocol.CommandSpec;
import io.github.itsnotethann.skord.protocol.DiscordPermission;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.jetbrains.annotations.Nullable;

/** What the protocol's plain command types mean to JDA and to Skript. The protocol itself knows neither. */
public final class JdaSpecs {

	private static final ChannelType[] MESSAGE_CHANNELS = {ChannelType.TEXT, ChannelType.NEWS,
		ChannelType.GUILD_PUBLIC_THREAD, ChannelType.GUILD_PRIVATE_THREAD, ChannelType.GUILD_NEWS_THREAD,
		ChannelType.VOICE};

	private JdaSpecs() {}

	public static OptionType type(ArgKind kind) {
		return switch (kind) {
			case TEXT -> OptionType.STRING;
			case INTEGER -> OptionType.INTEGER;
			case NUMBER -> OptionType.NUMBER;
			case BOOLEAN -> OptionType.BOOLEAN;
			case USER, MEMBER -> OptionType.USER;
			case ROLE -> OptionType.ROLE;
			case CHANNEL, CATEGORY -> OptionType.CHANNEL;
		};
	}

	/** The Skript-side Java type of a value of this kind. */
	public static Class<?> javaType(ArgKind kind) {
		return switch (kind) {
			case TEXT -> String.class;
			case INTEGER -> Long.class;
			case NUMBER -> Double.class;
			case BOOLEAN -> Boolean.class;
			case USER -> User.class;
			case MEMBER -> Member.class;
			case ROLE -> Role.class;
			case CHANNEL -> MessageChannel.class;
			case CATEGORY -> Category.class;
		};
	}

	public static OptionData toData(CommandSpec.Option option) {
		OptionData data = new OptionData(type(option.kind()), option.name(), option.description(), option.required());
		switch (option.kind()) {
			case CHANNEL -> data.setChannelTypes(MESSAGE_CHANNELS);
			case CATEGORY -> data.setChannelTypes(ChannelType.CATEGORY);
			default -> {}
		}
		return data;
	}

	public static EnumSet<Permission> permissions(EnumSet<DiscordPermission> permissions) {
		EnumSet<Permission> out = EnumSet.noneOf(Permission.class);
		for (DiscordPermission permission : permissions)
			out.add(Permission.valueOf(permission.name()));
		return out;
	}

	/** Converts an argument value as delivered to the declared type, e.g. a member to its user. */
	public static @Nullable Object convert(ArgKind kind, @Nullable Object value) {
		if (value == null || javaType(kind).isInstance(value))
			return value;
		if (kind == ArgKind.USER && value instanceof Member member)
			return member.getUser();
		return null;
	}
}
