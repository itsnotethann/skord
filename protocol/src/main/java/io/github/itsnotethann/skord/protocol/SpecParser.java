package io.github.itsnotethann.skord.protocol;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;

/**
 * Reads the text of a command declaration: names, {@code permission:}, {@code guild only:} and the
 * {@code arguments:} lines. It returns errors rather than printing them, so the bot and a game server each report
 * through their own Skript and the two can't drift.
 */
public final class SpecParser {

	private static final Pattern NAME = Pattern.compile("[-_\\p{Ll}\\p{Lo}\\p{N}]{1,32}");
	// reason: text?  "Why"
	private static final Pattern ARGUMENT =
		Pattern.compile("([-_\\p{L}\\p{N}]+)\\s*:\\s*([a-zA-Z]+)\\s*(\\?)?\\s*(?:\"((?:[^\"]|\"\")*)\")?\\s*");

	private SpecParser() {}

	/** A parsed value, or the message to show when there isn't one. */
	public record Result<T>(@Nullable T value, @Nullable String error) {

		public static <T> Result<T> ok(T value) {
			return new Result<>(value, null);
		}

		public static <T> Result<T> error(String message) {
			return new Result<>(null, message);
		}

		public boolean failed() {
			return error != null;
		}
	}

	public static boolean validName(String word) {
		return NAME.matcher(word).matches();
	}

	/** Splits {@code ticket close} into words, checking each and the three-word limit. */
	public static Result<List<String>> path(String raw) {
		List<String> path = List.of(raw.trim().split("\\s+"));
		for (String word : path) {
			if (!validName(word))
				return Result.error("'" + word + "' isn't a valid command name: use 1 to 32 lowercase letters, digits, '-' or '_'");
		}
		if (path.size() > 3)
			return Result.error("A command name has at most three words: command, group and subcommand");
		return Result.ok(path);
	}

	public static Result<EnumSet<DiscordPermission>> permissions(String text) {
		EnumSet<DiscordPermission> permissions = EnumSet.noneOf(DiscordPermission.class);
		for (String part : text.split("\\s*(?:,|\\band\\b)\\s*")) {
			if (part.isBlank())
				continue;
			DiscordPermission found = DiscordPermission.byName(part);
			if (found == null)
				return Result.error("Unknown permission '" + part.trim()
					+ "'. Use Discord's name for it, such as 'ban members' or 'manage channels'");
			permissions.add(found);
		}
		return Result.ok(permissions);
	}

	public static @Nullable Boolean bool(String text) {
		return switch (text.trim().toLowerCase(Locale.ROOT)) {
			case "true", "yes" -> true;
			case "false", "no" -> false;
			default -> null;
		};
	}

	/** One {@code arguments:} line, such as {@code reason: text? "Why"}. */
	public static Result<CommandSpec.Option> option(String line) {
		Matcher matcher = ARGUMENT.matcher(line.trim());
		if (!matcher.matches())
			return Result.error("Can't read the argument '" + line.trim() + "'. Write it as: name: type \"description\", "
				+ "with a ? after the type if it's optional");
		String name = matcher.group(1);
		ArgKind kind = ArgKind.byName(matcher.group(2));
		boolean required = matcher.group(3) == null;
		String description = matcher.group(4) == null ? name : matcher.group(4).replace("\"\"", "\"");
		if (!NAME.matcher(name).matches())
			return Result.error("'" + name + "' isn't a valid argument name: use 1 to 32 lowercase letters, digits, '-' or '_'");
		if (kind == null)
			return Result.error("Unknown argument type '" + matcher.group(2) + "'. Use one of: " + ArgKind.names());
		return Result.ok(new CommandSpec.Option(name, kind, required, description));
	}

	/** Checks the order and uniqueness rules over a whole argument list, one option at a time. */
	public static final class Arguments {

		private final List<CommandSpec.Option> options = new ArrayList<>();
		private boolean optionalSeen;

		/** Returns an error message, or null once the option has been taken. */
		public @Nullable String add(CommandSpec.Option option) {
			if (option.required() && optionalSeen)
				return "Required argument '" + option.name() + "' comes after an optional one. Discord needs required arguments first";
			optionalSeen |= !option.required();
			for (CommandSpec.Option other : options) {
				if (other.name().equals(option.name()))
					return "There are two arguments called '" + option.name() + "'";
			}
			options.add(option);
			return null;
		}

		public List<CommandSpec.Option> options() {
			return List.copyOf(options);
		}
	}
}
