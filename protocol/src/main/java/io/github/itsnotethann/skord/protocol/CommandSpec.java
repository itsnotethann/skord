package io.github.itsnotethann.skord.protocol;

import java.util.EnumSet;
import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * One command, parsed: everything Discord needs to register it, plus where it was declared.
 *
 * @param path        the name split into words: {@code [ticket]}, {@code [ticket, close]} or
 *                    {@code [admin, ticket, close]} (root, group, subcommand)
 * @param permissions who may see the command by default; empty means everyone. Root commands only.
 * @param guildOnly   hide the command in DMs. Root commands only.
 * @param script      where it was declared, for messages; a server's own file name when it came over the bridge
 * @param implicit    declared by an {@code on slash command} event alone, with no structure
 */
public record CommandSpec(List<String> path, String description, EnumSet<DiscordPermission> permissions,
						  boolean guildOnly, List<Option> options, String script, int line, boolean implicit) {

	/** Whether two specs declare the same command, ignoring where each was written. */
	public boolean sameDefinition(CommandSpec other) {
		return path.equals(other.path) && description.equals(other.description) && permissions.equals(other.permissions)
			&& guildOnly == other.guildOnly && options.equals(other.options);
	}

	public String name() {
		return String.join(" ", path);
	}

	public String where() {
		return script + (line > 0 ? ", line " + line : "");
	}

	public @Nullable Option option(String name) {
		for (Option option : options) {
			if (option.name().equals(name))
				return option;
		}
		return null;
	}

	public record Option(String name, ArgKind kind, boolean required, String description) {}
}
