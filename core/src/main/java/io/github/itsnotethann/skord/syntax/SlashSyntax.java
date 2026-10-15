package io.github.itsnotethann.skord.syntax;

import java.io.File;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.Skript;
import ch.njol.skript.config.Node;
import ch.njol.skript.config.SectionNode;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionType;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.parser.ParserInstance;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.skript.registrations.EventValues;
import ch.njol.util.Kleenean;
import io.github.itsnotethann.skord.command.CommandRegistry;
import io.github.itsnotethann.skord.command.JdaSpecs;
import io.github.itsnotethann.skord.event.SlashEvent;
import io.github.itsnotethann.skord.protocol.ArgKind;
import io.github.itsnotethann.skord.protocol.CommandSpec;
import io.github.itsnotethann.skord.protocol.CommandSpec.Option;
import io.github.itsnotethann.skord.protocol.DiscordPermission;
import io.github.itsnotethann.skord.protocol.SpecParser;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.entry.EntryContainer;
import org.skriptlang.skript.lang.entry.EntryValidator;
import org.skriptlang.skript.lang.event.PlatformEvent;
import org.skriptlang.skript.lang.script.Script;
import org.skriptlang.skript.lang.structure.Structure;

/**
 * Slash commands: the {@code slash command} structure that declares one, the {@code on slash command} event,
 * and {@code arg-<name>}.
 */
final class SlashSyntax {

	private SlashSyntax() {}

	static void register() {
		Skript.registerStructure(StructSlashCommand.class, EntryValidator.builder()
				.addEntry("description", null, true)
				.addEntry("permission", null, true)
				.addEntry("guild only", null, true)
				.addSection("arguments", true)
				.addSection("trigger", true)
				.build(),
			"slash command <.+>");
		Skript.registerEvent("Slash Command", EvtSlashCommand.class, SlashEvent.class, "slash command %string%");
		EventValues.registerEventValue(SlashEvent.class, User.class, SlashEvent::user);
		EventValues.registerEventValue(SlashEvent.class, Member.class, SlashEvent::member);
		EventValues.registerEventValue(SlashEvent.class, MessageChannel.class, SlashEvent::channel);
		EventValues.registerEventValue(SlashEvent.class, Guild.class, SlashEvent::guild);
		Skript.registerExpression(ExprArgument.class, Object.class, ExpressionType.SIMPLE, "[the] arg[ument]-<[-_\\p{L}\\p{N}]+>");
	}

	// The command whose trigger is being parsed, so arg-<name> can check the name and take the argument's type.
	private static final ThreadLocal<CommandSpec> PARSING = new ThreadLocal<>();

	/**
	 * <pre>
	 * slash command ban:
	 *     description: Ban a member
	 *     permission: ban members
	 *     guild only: true
	 *     arguments:
	 *         member: member  "Who to ban"
	 *         reason: text?   "Why"
	 *     trigger:
	 *         ...
	 * </pre>
	 * Up to three words name a subcommand: {@code slash command ticket close:}. Every entry is optional; a
	 * structure with only a description describes a root command whose subcommands live elsewhere.
	 */
	public static final class StructSlashCommand extends Structure {

		private CommandSpec spec;
		private @Nullable SectionNode trigger;
		private boolean registered;

		@Override
		public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult, EntryContainer entries) {
			SpecParser.Result<List<String>> parsedPath = SpecParser.path(parseResult.regexes.getFirst().group());
			if (parsedPath.failed()) {
				Skript.error(parsedPath.error());
				return false;
			}
			List<String> path = parsedPath.value();
			String name = String.join(" ", path);

			String description = entries.getOptional("description", String.class, false);
			String permissionText = entries.getOptional("permission", String.class, false);
			String guildOnlyText = entries.getOptional("guild only", String.class, false);
			if (path.size() > 1 && (permissionText != null || guildOnlyText != null)) {
				Skript.error("'permission' and 'guild only' apply to a whole command: put them on 'slash command "
					+ path.getFirst() + "'");
				return false;
			}
			EnumSet<DiscordPermission> permissions = EnumSet.noneOf(DiscordPermission.class);
			if (permissionText != null) {
				SpecParser.Result<EnumSet<DiscordPermission>> parsedPermissions = SpecParser.permissions(permissionText);
				if (parsedPermissions.failed()) {
					Skript.error(parsedPermissions.error());
					return false;
				}
				permissions = parsedPermissions.value();
			}
			boolean guildOnly = false;
			if (guildOnlyText != null) {
				Boolean parsed = SpecParser.bool(guildOnlyText);
				if (parsed == null) {
					Skript.error("'guild only' must be true or false, not '" + guildOnlyText + "'");
					return false;
				}
				guildOnly = parsed;
			}

			SectionNode arguments = entries.getOptional("arguments", SectionNode.class, false);
			List<Option> options = arguments == null ? List.of() : options(arguments);
			if (options == null)
				return false;
			trigger = entries.getOptional("trigger", SectionNode.class, false);

			Script script = getParser().getCurrentScript();
			spec = new CommandSpec(path, description == null ? name : description, permissions, guildOnly, options,
				new File(script.getConfig().getFileName()).getName(), entries.getSource().getLine(), false);
			return validate(spec);
		}

		@Override
		public boolean preLoad() {
			registered = CommandRegistry.register(spec, trigger != null || !spec.options().isEmpty());
			return registered;
		}

		@Override
		public boolean load() {
			if (trigger == null)
				return true;
			ParserInstance parser = getParser();
			PARSING.set(spec);
			try {
				parser.setCurrentEvent("slash command", SlashEvent.class);
				parser.setHasDelayBefore(Kleenean.FALSE);
				List<TriggerItem> items = ScriptLoader.loadItems(trigger);
				Script script = parser.getCurrentScript();
				Trigger loaded = new Trigger(script, "slash command " + spec.name(), new SimpleEvent(), items);
				loaded.setLineNumber(trigger.getLine());
				loaded.setDebugLabel(script + ": line " + trigger.getLine());
				CommandRegistry.setTrigger(spec, loaded);
			} finally {
				parser.deleteCurrentEvent();
				PARSING.remove();
			}
			return true;
		}

		@Override
		public void unload() {
			if (registered)
				CommandRegistry.unregister(spec);
			registered = false;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "slash command " + (spec == null ? "?" : spec.name());
		}

		private static @Nullable List<Option> options(SectionNode section) {
			SpecParser.Arguments arguments = new SpecParser.Arguments();
			for (Node node : section) {
				String line = node.getKey();
				if (line == null)
					continue;
				SpecParser.Result<Option> parsed = SpecParser.option(ScriptLoader.replaceOptions(line));
				String error = parsed.failed() ? parsed.error() : arguments.add(parsed.value());
				if (error != null) {
					Skript.error(error);
					return null;
				}
			}
			return arguments.options();
		}

		// Discord's own limits (lengths, counts) are checked by JDA as the data is built; report them as load errors.
		private static boolean validate(CommandSpec spec) {
			try {
				if (spec.path().size() == 1) {
					var data = Commands.slash(spec.name(), spec.description());
					spec.options().forEach(option -> data.addOptions(JdaSpecs.toData(option)));
				} else {
					var data = new SubcommandData(spec.path().getLast(), spec.description());
					spec.options().forEach(option -> data.addOptions(JdaSpecs.toData(option)));
				}
				return true;
			} catch (IllegalArgumentException e) {
				Skript.error("/" + spec.name() + ": " + e.getMessage());
				return false;
			}
		}
	}

	/**
	 * {@code on slash command "ping":}. Runs alongside the structure's own trigger. A command that no structure
	 * declares is registered bare, with no arguments, so a one-line command needs no structure.
	 */
	public static final class EvtSlashCommand extends SkriptEvent {

		private String name;

		@Override
		public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult) {
			name = String.join(" ", ((String) args[0].getSingle()).trim().toLowerCase(Locale.ROOT).split("\\s+"));
			for (String word : name.split(" ")) {
				if (!SpecParser.validName(word)) {
					Skript.error("'" + word + "' isn't a valid command name: use 1 to 32 lowercase letters, digits, '-' or '_'");
					return false;
				}
			}
			return true;
		}

		@Override
		public boolean load() {
			CommandSpec spec = CommandRegistry.spec(name);
			if (spec != null)
				PARSING.set(spec);
			try {
				return super.load();
			} finally {
				PARSING.remove();
			}
		}

		@Override
		public boolean postLoad() {
			CommandRegistry.addImplicit(name);
			return super.postLoad();
		}

		@Override
		public void unload() {
			CommandRegistry.removeImplicit(name);
			super.unload();
		}

		@Override
		public boolean check(PlatformEvent event) {
			return event instanceof SlashEvent slash && slash.command().equals(name);
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "slash command \"" + name + "\"";
		}
	}

	/**
	 * {@code arg-reason}: an argument's value, typed by its declaration. Outside a structure's trigger the
	 * command may be declared anywhere, so the value is untyped.
	 */
	public static final class ExprArgument extends SimpleExpression<Object> {

		private String name;
		private @Nullable ArgKind kind;

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			if (!getParser().isCurrentEvent(SlashEvent.class)) {
				Skript.error("arg-<name> only works in a slash command");
				return false;
			}
			name = parseResult.regexes.getFirst().group();
			CommandSpec spec = PARSING.get();
			if (spec != null) {
				Option option = spec.option(name);
				if (option == null) {
					Skript.error("/" + spec.name() + " has no argument called '" + name + "'");
					return false;
				}
				kind = option.kind();
			}
			return true;
		}

		@Override
		protected Object @Nullable [] get(PlatformEvent event) {
			if (!(event instanceof SlashEvent slash))
				return null;
			Object value = slash.option(name);
			if (kind != null)
				value = JdaSpecs.convert(kind, value);
			if (value == null)
				return null;
			Object[] array = (Object[]) java.lang.reflect.Array.newInstance(getReturnType(), 1);
			array[0] = value;
			return array;
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<?> getReturnType() {
			return kind == null ? Object.class : JdaSpecs.javaType(kind);
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "arg-" + name;
		}
	}
}
