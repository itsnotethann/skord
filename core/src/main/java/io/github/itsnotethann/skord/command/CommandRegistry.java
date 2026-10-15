package io.github.itsnotethann.skord.command;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Trigger;
import io.github.itsnotethann.skord.protocol.Frame;
import io.github.itsnotethann.skord.protocol.CommandSpec;
import io.github.itsnotethann.skord.protocol.DiscordPermission;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;
import org.jetbrains.annotations.Nullable;

/**
 * Every slash command the loaded scripts declare, and the trigger each one runs. Scripts register on load and
 * unregister on unload; {@link CommandSync} turns the current set into one Discord update.
 */
public final class CommandRegistry {

	// owner is the linked server that declared the command; null for a script's own. Owned commands have no
	// trigger here: using one sends the interaction to the server.
	private record Entry(CommandSpec spec, boolean invocable, @Nullable Trigger trigger, @Nullable String owner) {

		String where() {
			return owner == null ? spec.where() : "server '" + owner + "', " + spec.where();
		}
	}

	private static final Map<String, Entry> COMMANDS = new HashMap<>();
	// Names that only 'on slash command' events mention, with how many events mention each.
	private static final Map<String, Integer> IMPLICIT = new HashMap<>();

	private CommandRegistry() {}

	/**
	 * Registers a structure's command, or prints a load error and returns false if it clashes with one already
	 * registered. {@code invocable} means it has a trigger or arguments, so it can't also have subcommands.
	 */
	public static synchronized boolean register(CommandSpec spec, boolean invocable) {
		String error = conflict(spec, invocable);
		if (error != null) {
			Skript.error(error);
			return false;
		}
		COMMANDS.put(spec.name(), new Entry(spec, invocable, null, null));
		return true;
	}

	// Why this command can't be registered next to the ones already here, or null if it can.
	private static @Nullable String conflict(CommandSpec spec, boolean invocable) {
		String name = spec.name();
		Entry existing = COMMANDS.get(name);
		if (existing != null)
			return "/" + name + " is already defined in " + existing.where() + ". Each command can be defined once.";
		for (Entry other : COMMANDS.values()) {
			List<String> path = spec.path();
			List<String> otherPath = other.spec().path();
			if (isPrefix(otherPath, path) && (other.invocable() || otherPath.size() == 2))
				return "/" + name + " can't be a subcommand: /" + other.spec().name() + " ("
					+ other.where() + ") " + (other.invocable() ? "has its own trigger or arguments" : "is a subcommand")
					+ ", and Discord doesn't allow both.";
			if (isPrefix(path, otherPath) && (invocable || path.size() == 2))
				return "/" + name + " can't " + (invocable ? "have a trigger or arguments" : "be a subcommand")
					+ ": /" + other.spec().name() + " (" + other.where() + ") is a subcommand of it, "
					+ "and Discord doesn't allow both.";
		}
		return null;
	}

	/**
	 * Replaces everything {@code owner} had registered with {@code specs}, and returns the ones that clash with
	 * another owner's or a script's. A rejected command is left out; the rest are taken.
	 */
	public static synchronized List<Frame.CommandsResult.Rejection> replaceRemote(String owner, List<CommandSpec> specs) {
		COMMANDS.values().removeIf(entry -> owner.equals(entry.owner()));
		List<Frame.CommandsResult.Rejection> rejected = new ArrayList<>();
		for (CommandSpec spec : specs) {
			// A command with something under it is a group; otherwise it can be run, so it can't gain subcommands.
			boolean group = specs.stream().anyMatch(other -> isPrefix(spec.path(), other.path()));
			String error = conflict(spec, !group);
			if (error != null) {
				rejected.add(new Frame.CommandsResult.Rejection(spec.name(), error));
				continue;
			}
			COMMANDS.put(spec.name(), new Entry(spec, !group, null, owner));
		}
		return rejected;
	}

	/** Removes everything {@code owner} registered. Returns whether there was anything. */
	public static synchronized boolean forgetRemote(String owner) {
		return COMMANDS.values().removeIf(entry -> owner.equals(entry.owner()));
	}

	/** Removes every server-owned command, as a fresh start would have none yet. */
	public static synchronized void clearRemote() {
		COMMANDS.values().removeIf(entry -> entry.owner() != null);
	}

	public static synchronized List<CommandSpec> remoteSpecs(String owner) {
		return COMMANDS.values().stream().filter(entry -> owner.equals(entry.owner()))
			.map(Entry::spec).sorted(java.util.Comparator.comparing(CommandSpec::name)).toList();
	}

	/** The server that owns this command, or null for a script's own (or an unknown name). */
	public static synchronized @Nullable String owner(String name) {
		Entry entry = COMMANDS.get(name);
		return entry == null ? null : entry.owner();
	}

	public static synchronized void setTrigger(CommandSpec spec, Trigger trigger) {
		Entry entry = COMMANDS.get(spec.name());
		if (entry != null && entry.spec() == spec)
			COMMANDS.put(spec.name(), new Entry(spec, entry.invocable(), trigger, entry.owner()));
	}

	public static synchronized void unregister(CommandSpec spec) {
		Entry entry = COMMANDS.get(spec.name());
		if (entry != null && entry.spec() == spec)
			COMMANDS.remove(spec.name());
	}

	public static synchronized void addImplicit(String name) {
		IMPLICIT.merge(name, 1, Integer::sum);
	}

	public static synchronized void removeImplicit(String name) {
		IMPLICIT.computeIfPresent(name, (key, count) -> count > 1 ? count - 1 : null);
	}

	public static synchronized @Nullable CommandSpec spec(String name) {
		Entry entry = COMMANDS.get(name);
		return entry == null ? null : entry.spec();
	}

	public static synchronized @Nullable Trigger trigger(String name) {
		Entry entry = COMMANDS.get(name);
		return entry == null ? null : entry.trigger();
	}

	/** The commands to send Discord, in a stable order so equal sets hash equally. */
	public static synchronized List<CommandData> build() {
		Map<String, CommandSpec> all = new TreeMap<>();
		for (String name : IMPLICIT.keySet())
			all.put(name, implicit(name));
		for (Entry entry : COMMANDS.values())
			all.put(entry.spec().name(), entry.spec());

		Map<String, List<CommandSpec>> byRoot = new TreeMap<>();
		for (CommandSpec spec : all.values())
			byRoot.computeIfAbsent(spec.path().getFirst(), key -> new ArrayList<>()).add(spec);

		List<CommandData> commands = new ArrayList<>();
		for (Map.Entry<String, List<CommandSpec>> root : byRoot.entrySet()) {
			CommandSpec top = all.get(root.getKey());
			SlashCommandData data = Commands.slash(root.getKey(), top != null ? top.description() : root.getKey());
			if (top != null) {
				top.options().forEach(option -> data.addOptions(JdaSpecs.toData(option)));
				if (!top.permissions().isEmpty())
					data.setDefaultPermissions(DefaultMemberPermissions.enabledFor(JdaSpecs.permissions(top.permissions())));
				if (top.guildOnly())
					data.setContexts(InteractionContextType.GUILD);
			}
			Map<String, SubcommandGroupData> groups = new LinkedHashMap<>();
			for (CommandSpec spec : root.getValue()) {
				List<String> path = spec.path();
				if (path.size() == 1)
					continue;
				SubcommandData sub = new SubcommandData(path.getLast(), spec.description());
				spec.options().forEach(option -> sub.addOptions(JdaSpecs.toData(option)));
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

	/** Every registered name, for messages and tests. */
	public static synchronized List<String> names() {
		TreeSet<String> names = new TreeSet<>(IMPLICIT.keySet());
		names.addAll(COMMANDS.keySet());
		return List.copyOf(names);
	}

	private static CommandSpec implicit(String name) {
		return new CommandSpec(List.of(name.split(" ")), name, EnumSet.noneOf(DiscordPermission.class),
			false, List.of(), "an 'on slash command' event", 0, true);
	}

	private static boolean isPrefix(List<String> prefix, List<String> path) {
		return prefix.size() < path.size() && path.subList(0, prefix.size()).equals(prefix);
	}
}
