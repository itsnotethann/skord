package io.github.itsnotethann.skord.testbot;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionType;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.util.LiteralUtils;
import ch.njol.skript.util.Timespan;
import ch.njol.util.Kleenean;
import io.github.itsnotethann.skord.Console;
import io.github.itsnotethann.skord.command.CommandRegistry;
import io.github.itsnotethann.skord.protocol.CommandSpec;
import io.github.itsnotethann.skord.event.SkordEvent;
import io.github.itsnotethann.skord.event.SlashEvent;
import io.github.itsnotethann.skord.interaction.Interaction;
import io.github.itsnotethann.skord.rest.RestEffect;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.PlatformEvent;
import org.skriptlang.skript.lang.script.Script;

/** Syntax only test scripts have: recording, firing fake events, and driving the fake Discord. */
final class TestSyntax {

	private TestSyntax() {}

	static void register() {
		Skript.registerEvent("Test",EvtTest.class, TestEvent.class, "test %string%");
		Skript.registerEffect(EffMark.class, "mark %objects%");
		Skript.registerEffect(EffFireTest.class, "fire test event %string%");
		Skript.registerEffect(EffFireSlash.class, "fire slash command %string% [with options %-strings%] [as user %-string%]");
		Skript.registerEffect(EffFakeRequest.class, RestEffect.rest("fake request %string%"));
		Skript.registerEffect(EffSettle.class, "complete fake request %string% with %string%",
			"fail fake request %string% with %string%",
			"fail fake request %string% with discord error code %number%",
			"fail fake request %string% with missing permission %discordpermission%",
			"fail fake request %string% with a hierarchy error");
		Skript.registerEffect(EffStall.class, "stall [the] script thread for %timespan%");
		Skript.registerEffect(EffConsole.class, RestEffect.rest("run console command %string%"));
		Skript.registerExpression(ExprCommandRequests.class, Long.class, ExpressionType.SIMPLE, "[the] command requests");
		Skript.registerExpression(ExprSyncedCommands.class, String.class, ExpressionType.SIMPLE, "[the] synced commands");
		Skript.registerEffect(EffFakeServer.class,
			RestEffect.rest("connect fake server %string%"),
			RestEffect.rest("disconnect fake server %string%"),
			RestEffect.rest("publish commands of fake server %string%"),
			RestEffect.rest("connect fake server %string% allowing %string%"));
		Skript.registerEffect(EffFakePlayer.class,
			"fake server %string% reports player %string% joining",
			"fake server %string% reports player %string% leaving");
		Skript.registerExpression(ExprLinkOutput.class, String.class, ExpressionType.SIMPLE,
			"[the] output of sk link %string%");
		Skript.registerExpression(ExprKnownPlayers.class, String.class, ExpressionType.SIMPLE,
			"[the] known players of server %string%");
		Skript.registerExpression(ExprGrantedScope.class, String.class, ExpressionType.SIMPLE,
			"[the] granted scope of fake server %string%");
		Skript.registerEffect(EffDeclare.class,
			"fake server %string% declares command %string% [with arguments %-string%] and behaviour %string%");
		Skript.registerEffect(EffRestartBot.class, "simulate a bot restart");
		Skript.registerEffect(EffFakeServerData.class,
			"fake server %string% sends message %string% [with %-string%]",
			"fake server %string% listens for message %string%",
			"fake server %string% answers request %string% with %string%");
		Skript.registerEffect(EffFakeAsk.class, RestEffect.rest("fake server %string% asks the bot for %string% [with %-string%]"));
		Skript.registerExpression(ExprRejections.class, String.class, ExpressionType.SIMPLE,
			"[the] rejections of fake server %string%");
		Skript.registerEffect(EffServerProcess.class,
			RestEffect.rest("start server process %string% [allowing %-string%] [listening to %-string%] "
	+ "[using scripts %-string%]"),
			RestEffect.rest("reload server process %string%"));
		Skript.registerEffect(EffFireMessage.class,
			"fire message event in channel %string% with text %string%");
		Skript.registerEffect(EffRawSubscribe.class, "fake server %string% subscribes to %strings%");
		Skript.registerExpression(ExprBridgeCount.class, Long.class, ExpressionType.SIMPLE,
			"[the] forwarded events of server %string%",
			"[the] refused subscriptions of server %string%",
			"[the] fake discord calls of %string%");
		Skript.registerExpression(ExprRefusals.class, String.class, ExpressionType.SIMPLE,
		"[the] subscription refusals of fake server %string%");
		TestHeldSyntax.register();
		}

	static String scriptName(@Nullable Script script) {
		return script == null ? "?" : new File(script.getConfig().getFileName()).getName();
	}

	public static final class TestEvent extends SkordEvent {

		final String name;

		TestEvent(String name) {
			this.name = name;
		}
	}

	public static final class EvtTest extends SkriptEvent {

		private String name;

		@Override
		public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult) {
			name = (String) args[0].getSingle();
			return true;
		}

		@Override
		public boolean check(PlatformEvent event) {
			return event instanceof TestEvent test && test.name.equals(name);
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "test " + name;
		}
	}

	public static final class EffMark extends Effect {

		private Expression<?> values;
		private String script;

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			values = LiteralUtils.defendExpression(exprs[0]);
			script = scriptName(getParser().getCurrentScript());
			return LiteralUtils.canInitSafely(values);
		}

		@Override
		protected void execute(PlatformEvent event) {
			TestBot.recorder().mark(script, Arrays.stream(values.getArray(event))
				.map(Classes::toString)
				.collect(Collectors.joining(" ")));
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "mark " + values.toString(event, debug);
		}
	}

	public static final class EffFireTest extends Effect {

		private Expression<String> name;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			name = (Expression<String>) exprs[0];
			return true;
		}

		@Override
		protected void execute(PlatformEvent event) {
			String value = name.getSingle(event);
			if (value != null)
				Skript.eventBus().fire(new TestEvent(value));
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "fire test event " + name.toString(event, debug);
		}
	}

	/**
	 * Fires a slash command whose responses the fake Discord records under this script. Options are
	 * {@code "name=value"}, converted to the type the command declares.
	 */
	public static final class EffFireSlash extends Effect {

		private Expression<String> name;
		private @Nullable Expression<String> options;
		private @Nullable Expression<String> user;
		private String script;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			name = (Expression<String>) exprs[0];
			options = (Expression<String>) exprs[1];
			user = (Expression<String>) exprs[2];
			script = scriptName(getParser().getCurrentScript());
			return true;
		}

		@Override
		protected void execute(PlatformEvent event) {
			String command = name.getSingle(event);
			if (command == null)
				return;
			CommandSpec spec = CommandRegistry.spec(command);
			Map<String, Object> values = new HashMap<>();
			for (String option : options == null ? new String[0] : options.getArray(event)) {
				String[] parts = option.split("=", 2);
				CommandSpec.Option declared = spec == null ? null : spec.option(parts[0]);
				String raw = parts.length > 1 ? parts[1] : "";
				Object value = declared == null ? raw : switch (declared.kind()) {
					case INTEGER -> Long.parseLong(raw);
					case NUMBER -> Double.parseDouble(raw);
					case BOOLEAN -> Boolean.parseBoolean(raw);
					default -> raw;
				};
				values.put(parts[0], value);
			}
			Interaction interaction = new Interaction("/" + command, FakeDiscord.responder(script));
			String userId = user == null ? null : user.getSingle(event);
io.github.itsnotethann.skord.discord.UserRef invoker = userId == null ? null
	: new io.github.itsnotethann.skord.discord.UserRef(Long.parseLong(userId), "tester");
Skript.eventBus().fire(new SlashEvent(command, interaction, values, invoker, null, null, null));
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "fire slash command " + name.toString(event, debug);
		}
	}

	public static final class EffFakeRequest extends RestEffect {

		private Expression<String> id;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			id = (Expression<String>) exprs[0];
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			String value = id.getSingle(event);
			return value == null ? null : FakeDiscord.request(value);
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "fake request " + id.toString(event, debug);
		}
	}

	/** Completes a fake request, or fails it with a plain message, a Discord error code, a missing permission or a hierarchy error. */
	public static final class EffSettle extends Effect {

		private Expression<String> id;
		private @Nullable Expression<?> value;
		private int pattern;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			id = (Expression<String>) exprs[0];
			value = exprs.length > 1 ? exprs[1] : null;
			pattern = matchedPattern;
			return true;
		}

		@Override
		protected void execute(PlatformEvent event) {
			String name = id.getSingle(event);
			Object result = value == null ? null : value.getSingle(event);
			if (name == null || (value != null && result == null))
				return;
			CompletableFuture<Object> request = FakeDiscord.request(name);
			switch (pattern) {
				case 0 -> request.complete(result);
				case 1 -> request.completeExceptionally(new FakeDiscord.FakeError((String) result));
				case 2 -> request.completeExceptionally(FakeDiscord.response(((Number) result).intValue()));
				case 3 -> request.completeExceptionally(new FakeDiscord.FakePermissionError(Permission.valueOf(((io.github.itsnotethann.skord.protocol.DiscordPermission) result).name())));
				default -> request.completeExceptionally(
					new HierarchyException("Can't modify a role with higher or equal highest role than yourself!"));
			}
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return (pattern == 0 ? "complete" : "fail") + " fake request " + id.toString(event, debug);
		}
	}

	/** Holds the script thread, the way a slow synchronous section would. */
	public static final class EffStall extends Effect {

		private Expression<Timespan> duration;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			duration = (Expression<Timespan>) exprs[0];
			return true;
		}

		@Override
		protected void execute(PlatformEvent event) {
			Timespan timespan = duration.getSingle(event);
			if (timespan == null)
				return;
			try {
				Thread.sleep(timespan.getAs(Timespan.TimePeriod.MILLISECOND));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "stall the script thread for " + duration.toString(event, debug);
		}
	}

	/** A line typed into the console, paused on until it finishes, including any reload and command sync. */
	public static final class EffConsole extends RestEffect {

		private Expression<String> line;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			line = (Expression<String>) exprs[0];
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			String value = line.getSingle(event);
			if (value == null)
				return null;
			// Run it as its own task, as the console does, rather than in the middle of this trigger's walk.
			CompletableFuture<Void> done = new CompletableFuture<>();
			Skript.scheduler().sync(() -> {
				Console.dispatch(value).whenComplete((result, error) -> {
					if (error != null)
						done.completeExceptionally(error);
					else
						done.complete(null);
				});
			}, 0, -1);
			return done;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "run console command " + line.toString(event, debug);
		}
	}

	public static final class ExprCommandRequests extends SimpleExpression<Long> {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			return true;
		}

		@Override
		protected Long[] get(PlatformEvent event) {
			return new Long[] {(long) FakeDiscord.commandRequests()};
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends Long> getReturnType() {
			return Long.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "the command requests";
		}
	}

	/** Connects, disconnects or publishes for a fake game server. Pauses until the bot has seen the result. */
	public static final class EffFakeServer extends RestEffect {

		private Expression<String> name;
		private @Nullable Expression<String> scope;
		private int action;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			name = (Expression<String>) exprs[0];
			scope = matchedPattern == 3 ? (Expression<String>) exprs[1] : null;
			action = matchedPattern;
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			String server = name.getSingle(event);
			if (server == null)
				return null;
			return switch (action) {
				case 0 -> TestBridge.connect(server);
				case 1 -> TestBridge.disconnect(server);
				case 3 -> TestBridge.connect(server, scope == null ? null : scope.getSingle(event));
				default -> TestBridge.publish(server);
			};
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "fake server " + name.toString(event, debug);
		}
	}

	/** Stages a command on a fake server; {@code publish commands} sends the staged set. */
	public static final class EffDeclare extends Effect {

		private Expression<String> server;
		private Expression<String> command;
		private @Nullable Expression<String> arguments;
		private Expression<String> behaviour;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			server = (Expression<String>) exprs[0];
			command = (Expression<String>) exprs[1];
			arguments = (Expression<String>) exprs[2];
			behaviour = (Expression<String>) exprs[3];
			return true;
		}

		@Override
		protected void execute(PlatformEvent event) {
			String name = server.getSingle(event);
			String spec = command.getSingle(event);
			String how = behaviour.getSingle(event);
			if (name != null && spec != null && how != null)
				TestBridge.declare(name, spec, arguments == null ? null : arguments.getSingle(event), how);
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "fake server declares " + command.toString(event, debug);
		}
	}

	/** A fake server sends a message, listens for one, or answers requests. Messages it hears are recorded. */
	public static final class EffFakeServerData extends Effect {

		private Expression<String> server;
		private Expression<String> name;
		private @Nullable Expression<String> data;
		private int action;
		private String script;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			server = (Expression<String>) exprs[0];
			name = (Expression<String>) exprs[1];
			data = exprs.length > 2 ? (Expression<String>) exprs[2] : null;
			action = matchedPattern;
			script = scriptName(getParser().getCurrentScript());
			return true;
		}

		@Override
		protected void execute(PlatformEvent event) {
			String serverName = server.getSingle(event);
			String thing = name.getSingle(event);
			String value = data == null ? null : data.getSingle(event);
			if (serverName == null || thing == null)
				return;
			switch (action) {
				case 0 -> {
					if (!TestBridge.sendMessage(serverName, thing, value))
						Skript.warning("fake server '" + serverName + "' could not send '" + thing + "'");
				}
				case 1 -> TestBridge.listen(serverName, thing, script);
				default -> TestBridge.answer(serverName, thing, value == null ? "" : value);
			}
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "fake server " + server.toString(event, debug) + " data " + name.toString(event, debug);
		}
	}

	/** The fake server asks the bot something and stores the answer, keeping its keys. */
	public static final class EffFakeAsk extends RestEffect {

		private Expression<String> server;
		private Expression<String> request;
		private @Nullable Expression<String> data;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			server = (Expression<String>) exprs[0];
			request = (Expression<String>) exprs[1];
			data = (Expression<String>) exprs[2];
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			String name = server.getSingle(event);
			String what = request.getSingle(event);
			if (name == null || what == null)
				return null;
			return TestBridge.ask(name, what, data == null ? null : data.getSingle(event));
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "fake server asks the bot for " + request.toString(event, debug);
		}
	}

	/** Forgets server-owned commands in memory and loads the saved ones again, as a fresh process would. */
	public static final class EffRestartBot extends Effect {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			return true;
		}

		@Override
		protected void execute(PlatformEvent event) {
			TestBridge.restartBot();
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "simulate a bot restart";
		}
	}

	public static final class ExprRejections extends SimpleExpression<String> {

		private Expression<String> name;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			name = (Expression<String>) exprs[0];
			return true;
		}

		@Override
		protected String[] get(PlatformEvent event) {
			String server = name.getSingle(event);
			return server == null ? new String[0] : TestBridge.rejections(server).toArray(String[]::new);
		}

		@Override
		public boolean isSingle() {
			return false;
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "the rejections of fake server " + name.toString(event, debug);
		}
	}

	public static final class EffFakePlayer extends Effect {

		private Expression<String> server;
		private Expression<String> player;
		private boolean joining;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			server = (Expression<String>) exprs[0];
			player = (Expression<String>) exprs[1];
			joining = matchedPattern == 0;
			return true;
		}

		@Override
		protected void execute(PlatformEvent event) {
			String name = server.getSingle(event);
			String who = player.getSingle(event);
			if (name != null && who != null)
				TestBridge.playerChange(name, who, joining);
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "fake server " + server.toString(event, debug) + " reports player " + player.toString(event, debug);
		}
	}

	public static final class ExprLinkOutput extends SimpleExpression<String> {

		private Expression<String> line;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			line = (Expression<String>) exprs[0];
			return true;
		}

		@Override
		protected String[] get(PlatformEvent event) {
			String value = line.getSingle(event);
			return value == null ? new String[0] : TestBridge.linkCommand(value).toArray(String[]::new);
		}

		@Override
		public boolean isSingle() {
			return false;
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "the output of sk link " + line.toString(event, debug);
		}
	}

	public static final class ExprKnownPlayers extends SimpleExpression<String> {

		private Expression<String> name;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			name = (Expression<String>) exprs[0];
			return true;
		}

		@Override
		protected String[] get(PlatformEvent event) {
			String server = name.getSingle(event);
			return server == null ? new String[0] : TestBridge.knownPlayers(server).toArray(String[]::new);
		}

		@Override
		public boolean isSingle() {
			return false;
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "the known players of server " + name.toString(event, debug);
		}
	}

	public static final class ExprGrantedScope extends SimpleExpression<String> {

		private Expression<String> name;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			name = (Expression<String>) exprs[0];
			return true;
		}

		@Override
		protected String[] get(PlatformEvent event) {
			String server = name.getSingle(event);
			return server == null ? new String[0] : TestBridge.grantedScope(server).toArray(String[]::new);
		}

		@Override
		public boolean isSingle() {
			return false;
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "the granted scope of fake server " + name.toString(event, debug);
		}
	}

	/** Starts or reloads a real server process (see {@link TestServer}); pauses until the bot has what it sent. */
	public static final class EffServerProcess extends RestEffect {

		private Expression<String> name;
		private @Nullable Expression<String> allow;
		private @Nullable Expression<String> listen;
		private @Nullable Expression<String> profile;
		private boolean reload;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			name = (Expression<String>) exprs[0];
			reload = matchedPattern == 1;
			if (!reload) {
				allow = (Expression<String>) exprs[1];
				listen = (Expression<String>) exprs[2];
profile = (Expression<String>) exprs[3];
			}
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			String server = name.getSingle(event);
			if (server == null)
				return null;
			if (reload)
				return ServerProcess.reload(server);
			return ServerProcess.start(server, allow == null ? null : allow.getSingle(event),
				listen == null ? null : listen.getSingle(event), profile == null ? null : profile.getSingle(event));
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return (reload ? "reload" : "start") + " server process " + name.toString(event, debug);
		}
	}

	/** A message arrives in a channel, as the gateway would deliver it to the bot. */
	public static final class EffFireMessage extends Effect {

		private static final java.util.concurrent.atomic.AtomicLong IDS =
			new java.util.concurrent.atomic.AtomicLong(700000000000000000L);

		private Expression<String> channel;
		private Expression<String> text;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			channel = (Expression<String>) exprs[0];
			text = (Expression<String>) exprs[1];
			return true;
		}

		@Override
		protected void execute(PlatformEvent event) {
			String id = channel.getSingle(event);
			String content = text.getSingle(event);
			if (id == null || content == null)
				return;
			long channelId = Long.parseLong(id);
			var message = new io.github.itsnotethann.skord.discord.MessageRef(IDS.incrementAndGet(), channelId,
				content, content);
			Skript.eventBus().fire(new io.github.itsnotethann.skord.event.MessageEvent(message,
				new io.github.itsnotethann.skord.discord.UserRef(444444444444444444L, "tester"),
				new io.github.itsnotethann.skord.discord.ChannelRef(channelId, "fake-channel"), null));
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "fire message event in channel " + channel.toString(event, debug);
		}
	}

	public static final class EffRawSubscribe extends Effect {

		private Expression<String> server;
		private Expression<String> specs;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			server = (Expression<String>) exprs[0];
			specs = (Expression<String>) exprs[1];
			return true;
		}

		@Override
		protected void execute(PlatformEvent event) {
			String name = server.getSingle(event);
			if (name != null)
				TestBridge.subscribe(name, specs.getArray(event));
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "fake server " + server.toString(event, debug) + " subscribes to " + specs.toString(event, debug);
		}
	}

	public static final class ExprBridgeCount extends SimpleExpression<Long> {

		private Expression<String> name;
		private int which;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			name = (Expression<String>) exprs[0];
			which = matchedPattern;
			return true;
		}

		@Override
		protected Long[] get(PlatformEvent event) {
			String key = name.getSingle(event);
			if (key == null)
				return new Long[0];
			return new Long[] {switch (which) {
				case 0 -> (long) TestBridge.router().forwardedEvents(key);
				case 1 -> (long) TestBridge.router().refusedSubscriptions(key);
				default -> (long) FakeDiscord.calls(key);
			}};
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends Long> getReturnType() {
			return Long.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "the bridge count of " + name.toString(event, debug);
		}
	}

	public static final class ExprRefusals extends SimpleExpression<String> {

		private Expression<String> name;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			name = (Expression<String>) exprs[0];
			return true;
		}

		@Override
		protected String[] get(PlatformEvent event) {
			String server = name.getSingle(event);
			return server == null ? new String[0] : TestBridge.subscriptionRefusals(server).toArray(String[]::new);
		}

		@Override
		public boolean isSingle() {
			return false;
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "the subscription refusals of fake server " + name.toString(event, debug);
		}
	}

	public static final class ExprSyncedCommands extends SimpleExpression<String> {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			return true;
		}

		@Override
		protected String[] get(PlatformEvent event) {
			return FakeDiscord.syncedCommands().toArray(String[]::new);
		}

		@Override
		public boolean isSingle() {
			return false;
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "the synced commands";
		}
	}
}
