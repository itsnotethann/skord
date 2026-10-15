package io.github.itsnotethann.skord.testbot;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionType;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import io.github.itsnotethann.skord.discord.DiscordEvent;
import io.github.itsnotethann.skord.discord.EventCompletion;
import io.github.itsnotethann.skord.discord.EventSpec;
import io.github.itsnotethann.skord.discord.HeldKinds;
import io.github.itsnotethann.skord.event.HeldEvent;
import io.github.itsnotethann.skord.event.SkordEvent;
import io.github.itsnotethann.skord.remote.HandlerTracker;
import io.github.itsnotethann.skord.remote.RemoteServer;
import io.github.itsnotethann.skord.rest.RestEffect;
import io.github.itsnotethann.skord.syntax.EventSpecs;
import io.github.itsnotethann.skord.syntax.SkordSyntax;
import io.github.itsnotethann.skord.syntax.SpecEvent;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.PlatformEvent;

/**
 * Held events and shared commands, as test syntax: a heldable kind that exists only here, ways to kill and feed a
 * server process, and the gates and withheld acks that make a handler die at a chosen point.
 */
final class TestHeldSyntax {

	private static final Map<String, CompletableFuture<Void>> GATES = new ConcurrentHashMap<>();

	private TestHeldSyntax() {}

	static void release(String gate) {
		gate(gate).complete(null);
	}

	private static CompletableFuture<Void> gate(String name) {
		return GATES.computeIfAbsent(name, key -> new CompletableFuture<>());
	}

	static void register() {
		HeldKinds.register(EventSpec.Kind.TEST_BOOST, data -> "test:" + data.get("n"));
		RemoteServer.factory(EventSpec.Kind.TEST_BOOST, event -> new TestBoostEvent(event.id(), event.redelivered(),
			String.valueOf(event.data().get("n"))));
		Skript.registerEvent("Test Boost", EvtTestBoost.class, TestBoostEvent.class, "test boost " + SkordSyntax.HELD);
		Skript.registerExpression(ExprBoostNumber.class, String.class, ExpressionType.SIMPLE, "[the] boost number");
		Skript.registerEffect(EffFireBoost.class, "fire test boost %string%");
		Skript.registerEffect(EffWithhold.class, "withhold the event ack");
		Skript.registerEffect(EffGate.class, RestEffect.rest("wait for gate %string%"));
		Skript.registerEffect(EffKill.class, RestEffect.rest("kill server process %string%"));
		Skript.registerEffect(EffProcessLine.class,
			"release gate %string% of server process %string%",
			"server process %string% reports player %string% joining",
			"server process %string% reports player %string% leaving");
		Skript.registerEffect(EffLinkPlayer.class, "link discord user %string% to player %string%");
		Skript.registerExpression(ExprHeld.class, String.class, ExpressionType.SIMPLE,
			"[the] held events of server %string%",
			"[the] held drops of server %string%");
	}

	public static final class TestBoostEvent extends SkordEvent implements HeldEvent {

		private final String id;
		private final boolean redelivered;
		private final String number;

		TestBoostEvent(String id, boolean redelivered, String number) {
			this.id = id;
			this.redelivered = redelivered;
			this.number = number;
		}

		@Override
		public String eventId() {
			return id;
		}

		@Override
		public boolean redelivered() {
			return redelivered;
		}

		String number() {
			return number;
		}
	}

	public static final class EvtTestBoost extends SkriptEvent implements SpecEvent {

		private boolean held;

		@Override
		public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult) {
			held = parseResult.hasTag("held");
			return true;
		}

		@Override
		public boolean check(PlatformEvent event) {
			return event instanceof TestBoostEvent;
		}

		@Override
		public EventSpec spec() {
			return new EventSpec(EventSpec.Kind.TEST_BOOST, Map.of(), held);
		}

		@Override
		public boolean postLoad() {
			EventSpecs.add(spec());
			return super.postLoad();
		}

		@Override
		public void unload() {
			EventSpecs.remove(spec());
			super.unload();
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "test boost" + (held ? " (held)" : "");
		}
	}

	public static final class ExprBoostNumber extends SimpleExpression<String> {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			return true;
		}

		@Override
		protected String @Nullable [] get(PlatformEvent event) {
			return event instanceof TestBoostEvent boost ? new String[] {boost.number()} : null;
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "the boost number";
		}
	}

	/** The bot's side: a boost happens, as the gateway would report it. */
	public static final class EffFireBoost extends Effect {

		private Expression<String> number;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			number = (Expression<String>) exprs[0];
			return true;
		}

		@Override
		protected void execute(PlatformEvent event) {
			String value = number.getSingle(event);
			if (value != null)
				FakeDiscord.bus().publishRaw(new DiscordEvent(EventSpec.Kind.TEST_BOOST, Map.of("n", value)));
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "fire test boost " + number.toString(event, debug);
		}
	}

	/** The server's side: the handler finishes, but its ack is never sent, as if the process died just before it. */
	public static final class EffWithhold extends Effect {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			return true;
		}

		@Override
		protected void execute(PlatformEvent event) {
			EventCompletion completion = HandlerTracker.of(event);
			if (completion != null)
				completion.begin();
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "withhold the event ack";
		}
	}

	/** The server's side: a tracked pause that ends when the bot's script releases the gate. */
	public static final class EffGate extends RestEffect {

		private Expression<String> name;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			name = (Expression<String>) exprs[0];
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			String value = name.getSingle(event);
			return value == null ? null : gate(value);
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "wait for gate " + name.toString(event, debug);
		}
	}

	public static final class EffKill extends RestEffect {

		private Expression<String> name;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			name = (Expression<String>) exprs[0];
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			String value = name.getSingle(event);
			return value == null ? null : ServerProcess.kill(value);
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "kill server process " + name.toString(event, debug);
		}
	}

	public static final class EffProcessLine extends Effect {

		private Expression<String> first;
		private @Nullable Expression<String> second;
		private int which;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			which = matchedPattern;
			first = (Expression<String>) exprs[0];
			second = (Expression<String>) exprs[1];
			return true;
		}

		@Override
		protected void execute(PlatformEvent event) {
			String a = first.getSingle(event);
			String b = second == null ? null : second.getSingle(event);
			if (a == null || b == null)
				return;
			switch (which) {
				case 0 -> ServerProcess.send(b, "release " + a);
				case 1 -> ServerProcess.send(a, "join " + b);
				default -> ServerProcess.send(a, "leave " + b);
			}
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "server process line";
		}
	}

	public static final class EffLinkPlayer extends Effect {

		private Expression<String> user;
		private Expression<String> player;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			user = (Expression<String>) exprs[0];
			player = (Expression<String>) exprs[1];
			return true;
		}

		@Override
		protected void execute(PlatformEvent event) {
			String id = user.getSingle(event);
			String name = player.getSingle(event);
			if (id != null && name != null)
				TestBridge.linkPlayer(id, name);
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "link discord user " + user.toString(event, debug);
		}
	}

	public static final class ExprHeld extends SimpleExpression<String> {

		private Expression<String> name;
		private boolean drops;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			name = (Expression<String>) exprs[0];
			drops = matchedPattern == 1;
			return true;
		}

		@Override
		protected String[] get(PlatformEvent event) {
			String server = name.getSingle(event);
			if (server == null)
				return new String[0];
			List<String> values = drops ? TestBridge.router().heldDrops(server) : TestBridge.router().heldIds(server);
			return values.toArray(String[]::new);
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
			return "the held " + (drops ? "drops" : "events") + " of server " + name.toString(event, debug);
		}
	}
}
