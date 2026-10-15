package io.github.itsnotethann.skord.command;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Trigger;
import io.github.itsnotethann.skord.protocol.Frame;
import io.github.itsnotethann.skord.protocol.CommandSpec;
import io.github.itsnotethann.skord.protocol.DiscordPermission;
import org.jetbrains.annotations.Nullable;

/**
 * Every slash command the loaded scripts declare, and the trigger each one runs. Scripts register on load and
 * unregister on unload; {@link CommandSync} turns the current set into one Discord update.
 */
public final class CommandRegistry {

	// owners are the linked servers that declared the command, in the order they did; empty for a script's own.
	// Owned commands have no trigger here: using one sends the interaction to a server.
	private record Entry(CommandSpec spec, boolean invocable, @Nullable Trigger trigger, List<String> owners) {

		String where() {
			return owners.isEmpty() ? spec.where() : "server '" + owners.get(0) + "', " + spec.where();
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
		COMMANDS.put(spec.name(), new Entry(spec, invocable, null, List.of()));
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
	 * Replaces everything {@code owner} had registered with {@code specs}, and returns the ones that clash with a
	 * script's or with another owner's different definition. A command several servers declare identically is
	 * shared. A rejected command is left out; the rest are taken.
	 */
	public static synchronized List<Frame.CommandsResult.Rejection> replaceRemote(String owner, List<CommandSpec> specs) {
		for (Map.Entry<String, Entry> each : new ArrayList<>(COMMANDS.entrySet())) {
			Entry entry = each.getValue();
			if (!entry.owners().contains(owner))
				continue;
			List<String> rest = new ArrayList<>(entry.owners());
			rest.remove(owner);
			if (rest.isEmpty())
				COMMANDS.remove(each.getKey());
			else
				COMMANDS.put(each.getKey(), new Entry(entry.spec(), entry.invocable(), entry.trigger(), List.copyOf(rest)));
		}
		List<Frame.CommandsResult.Rejection> rejected = new ArrayList<>();
		for (CommandSpec spec : specs) {
			Entry existing = COMMANDS.get(spec.name());
			if (existing != null && !existing.owners().isEmpty()) {
				if (existing.spec().sameDefinition(spec)) {
					List<String> owners = new ArrayList<>(existing.owners());
					owners.add(owner);
					COMMANDS.put(spec.name(), new Entry(existing.spec(), existing.invocable(), null, List.copyOf(owners)));
				} else {
					rejected.add(new Frame.CommandsResult.Rejection(spec.name(), "/" + spec.name() + " is declared by "
						+ describe(existing.owners()) + " (" + existing.spec().where() + ") with a different definition "
						+ "than server '" + owner + "' (" + spec.where() + "). Servers can share a command only when "
						+ "its declaration is identical."));
				}
				continue;
			}
			// A command with something under it is a group; otherwise it can be run, so it can't gain subcommands.
			boolean group = specs.stream().anyMatch(other -> isPrefix(spec.path(), other.path()));
			String error = conflict(spec, !group);
			if (error != null) {
				rejected.add(new Frame.CommandsResult.Rejection(spec.name(), error));
				continue;
			}
			COMMANDS.put(spec.name(), new Entry(spec, !group, null, List.of(owner)));
		}
		return rejected;
	}

	private static String describe(List<String> owners) {
		return (owners.size() == 1 ? "server " : "servers ") + String.join(", ", owners.stream()
			.map(name -> "'" + name + "'").toList());
	}

	/** Removes everything {@code owner} registered. Returns whether there was anything. */
	public static synchronized boolean forgetRemote(String owner) {
		boolean any = COMMANDS.values().stream().anyMatch(entry -> entry.owners().contains(owner));
		replaceRemote(owner, List.of());
		return any;
	}

	/** Removes every server-owned command, as a fresh start would have none yet. */
	public static synchronized void clearRemote() {
		COMMANDS.values().removeIf(entry -> !entry.owners().isEmpty());
	}

	public static synchronized List<CommandSpec> remoteSpecs(String owner) {
		return COMMANDS.values().stream().filter(entry -> entry.owners().contains(owner))
			.map(Entry::spec).sorted(java.util.Comparator.comparing(CommandSpec::name)).toList();
	}

	/** The servers that own this command, in the order they declared it; empty for a script's own or unknown name. */
	public static synchronized List<String> owners(String name) {
		Entry entry = COMMANDS.get(name);
		return entry == null ? List.of() : entry.owners();
	}

	public static synchronized void setTrigger(CommandSpec spec, Trigger trigger) {
		Entry entry = COMMANDS.get(spec.name());
		if (entry != null && entry.spec() == spec)
			COMMANDS.put(spec.name(), new Entry(spec, entry.invocable(), trigger, entry.owners()));
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
	public static synchronized Map<String, CommandSpec> specs() {
		Map<String, CommandSpec> all = new TreeMap<>();
		for (String name : IMPLICIT.keySet())
			all.put(name, implicit(name));
		for (Entry entry : COMMANDS.values())
			all.put(entry.spec().name(), entry.spec());
		return all;
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
