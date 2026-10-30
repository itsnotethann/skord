package io.github.itsnotethann.skord.command;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.itsnotethann.skord.protocol.ArgKind;
import io.github.itsnotethann.skord.protocol.CommandSpec;
import io.github.itsnotethann.skord.protocol.DiscordPermission;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;
import org.jetbrains.annotations.Nullable;

/** What the protocol's plain command types mean to JDA. The protocol itself knows neither. */
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

	/** The commands to send Discord, in a stable order so equal sets hash equally. */
	public static List<CommandData> build() {
		Map<String, CommandSpec> all = CommandRegistry.specs();

		Map<String, List<CommandSpec>> byRoot = new TreeMap<>();
		for (CommandSpec spec : all.values())
			byRoot.computeIfAbsent(spec.path().getFirst(), key -> new ArrayList<>()).add(spec);

		List<CommandData> commands = new ArrayList<>();
		for (Map.Entry<String, List<CommandSpec>> root : byRoot.entrySet()) {
			CommandSpec top = all.get(root.getKey());
			SlashCommandData data = Commands.slash(root.getKey(), top != null ? top.description() : root.getKey());
			if (top != null) {
				top.options().forEach(option -> data.addOptions(toData(option)));
				if (!top.permissions().isEmpty())
					data.setDefaultPermissions(DefaultMemberPermissions.enabledFor(permissions(top.permissions())));
				if (top.guildOnly())
					data.setContexts(InteractionContextType.GUILD);
			}
			Map<String, SubcommandGroupData> groups = new LinkedHashMap<>();
			for (CommandSpec spec : root.getValue()) {
				List<String> path = spec.path();
				if (path.size() == 1)
					continue;
				SubcommandData sub = new SubcommandData(path.getLast(), spec.description());
				spec.options().forEach(option -> sub.addOptions(toData(option)));
				if (path.size() == 2)
					data.addSubcommands(sub);
				else
					groups.computeIfAbsent(path.get(1), group -> new SubcommandGroupData(group, group)).addSubcommands(sub);
			}
			groups.values().forEach(data::addSubcommandGroups);
			commands.add(data);
		}
		return commands;
	}

	/** Why Discord would refuse this command's shape (lengths, counts), or null if it would take it. */
	public static @Nullable String problem(CommandSpec spec) {
		try {
			if (spec.path().size() == 1) {
				var data = Commands.slash(spec.name(), spec.description());
				spec.options().forEach(option -> data.addOptions(toData(option)));
			} else {
				var data = new SubcommandData(spec.path().getLast(), spec.description());
				spec.options().forEach(option -> data.addOptions(toData(option)));
			}
			return null;
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
	}
}
