package io.github.itsnotethann.skord.bridge;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import io.github.itsnotethann.skord.discord.DiscordError;
import io.github.itsnotethann.skord.discord.DiscordException;
import io.github.itsnotethann.skord.protocol.Frame;
import io.github.itsnotethann.skord.protocol.Scope;
import io.github.itsnotethann.skord.shared.SharedCalls;
import io.github.itsnotethann.skord.shared.SharedFunctions;
import io.github.itsnotethann.skord.shared.SharedValues;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BridgeCalls implements SharedCalls {

	private static final Logger LOGGER = LoggerFactory.getLogger("skord");

	private record Ask(String server, CompletableFuture<JsonElement> future) {}

	private record Relay(String origin, String originId, String target) {}

	private final Supplier<@Nullable BridgeServer> server;
	private final LinkStore links;
	private final RemoteCommandStore store;
	private final Executor script;
	private final ScheduledExecutorService timers;
	private final long timeoutMillis;
	private final Map<String, Ask> asks = new ConcurrentHashMap<>();
	private final Map<String, Relay> relays = new ConcurrentHashMap<>();
	private final Map<String, List<Frame.FunctionSpec>> known = new ConcurrentHashMap<>();

	BridgeCalls(Supplier<@Nullable BridgeServer> server, LinkStore links, RemoteCommandStore store, Executor script,
				ScheduledExecutorService timers, long timeoutMillis) {
		this.server = server;
		this.links = links;
		this.store = store;
		this.script = script;
		this.timers = timers;
		this.timeoutMillis = timeoutMillis;
	}

	static DiscordException failure(DiscordError.Type type, String message) {
		return new DiscordException(new DiscordError(type, message, null, 0));
	}

	private static Throwable unwrap(Throwable error) {
		while (error instanceof CompletionException && error.getCause() != null)
			error = error.getCause();
		return error;
	}

	private static Frame.Result.Failure wire(DiscordError error) {
		return new Frame.Result.Failure(error.type().label(), error.message(), error.code(), error.scope());
	}


	void restore(String name) {
		List<Frame.FunctionSpec> saved = store.loadFunctions(name);
		if (saved != null)
			known.put(name, saved);
	}

	void declared(String name, Frame.Functions frame) {
		known.put(name, List.copyOf(frame.functions()));
		store.saveFunctions(name, frame.functions());
	}

	void forget(String name) {
		known.remove(name);
	}

	void clearMemory() {
		known.clear();
	}

	void publishTo(String name) {
		BridgeServer live = server.get();
		if (live != null)
			live.send(name, new Frame.Functions(SharedFunctions.specs()));
	}

	public void publishAll() {
		BridgeServer live = server.get();
		if (live == null)
			return;
		Frame.Functions frame = new Frame.Functions(SharedFunctions.specs());
		for (String name : live.connectedServers())
			live.send(name, frame);
	}


	@Override
	public CompletionStage<JsonElement> call(String target, String function, JsonArray args) {
		if (target.equals("bot"))
			return CompletableFuture.failedFuture(failure(DiscordError.Type.ERROR,
				"The bot is this process; call '" + function + "' directly"));
		return ask(target, function, args, timeoutMillis);
	}

	@Override
	public void send(String target, String function, JsonArray args) {
		BridgeServer live = server.get();
		if (live == null || !live.send(target, new Frame.Call(UUID.randomUUID().toString(), function, args, false)))
			LOGGER.warn("Call to '{}' on '{}' was not sent: the server is offline", function, target);
	}

	@Override
	public CompletionStage<Map<String, Outcome>> all(String function, JsonArray args) {
		return askAll(function, args);
	}

	@Override
	public @Nullable List<Frame.FunctionSpec> functionsOf(String target) {
		return known.get(target);
	}

	CompletableFuture<JsonElement> ask(String target, String function, JsonElement args, long millis) {
		BridgeServer live = server.get();
		if (live == null || !live.isConnected(target))
			return CompletableFuture.failedFuture(failure(DiscordError.Type.OFFLINE,
				"The server '" + target + "' is offline right now"));
		String id = UUID.randomUUID().toString();
		CompletableFuture<JsonElement> future = new CompletableFuture<>();
		asks.put(id, new Ask(target, future));
		if (!live.send(target, new Frame.Call(id, function, args, true))) {
			asks.remove(id);
			return CompletableFuture.failedFuture(failure(DiscordError.Type.OFFLINE,
				"The server '" + target + "' is offline right now"));
		}
		timers.schedule(() -> {
			if (asks.remove(id) != null)
				future.completeExceptionally(failure(DiscordError.Type.TIMED_OUT,
					"The server '" + target + "' didn't answer '" + function + "' in time"));
		}, millis, TimeUnit.MILLISECONDS);
		return future;
	}

	private CompletableFuture<Map<String, Outcome>> askAll(String function, JsonElement args) {
		BridgeServer live = server.get();
		List<String> servers = live == null ? List.of() : live.connectedServers();
		Map<String, CompletableFuture<Outcome>> pending = new TreeMap<>();
		for (String name : servers) {
			pending.put(name, ask(name, function, args, timeoutMillis).handle((data, error) -> error == null
				? new Outcome(data, null) : new Outcome(null, DiscordError.of(unwrap(error)))));
		}
		return CompletableFuture.allOf(pending.values().toArray(CompletableFuture[]::new)).thenApply(done -> {
			Map<String, Outcome> out = new TreeMap<>();
			pending.forEach((name, outcome) -> out.put(name, outcome.join()));
			return out;
		});
	}


	void incoming(String origin, Frame.Call call) {
		BridgeServer live = server.get();
		if (live == null)
			return;
		if (!links.allows(origin, Scope.CALL)) {
			LOGGER.warn("Bridge: server '{}' called '{}' without the 'call' scope", origin, call.function());
			if (call.waiting())
				live.send(origin, new Frame.CallResult(call.id(), JsonNull.INSTANCE, new Frame.Result.Failure(
					DiscordError.Type.OUT_OF_SCOPE.label(), "This server's link does not allow 'call'", 0, Scope.CALL.wire)));
			return;
		}
		String target = call.target();
		if (target == null || target.equals("bot"))
			local(origin, call, live);
		else if (target.equals(Frame.Call.ALL))
			fanOut(origin, call, live);
		else
			relay(origin, call, target, live);
	}

	private void reply(BridgeServer live, String origin, Frame.Call call, DiscordError.Type type, String message) {
		if (call.waiting())
			live.send(origin, new Frame.CallResult(call.id(), JsonNull.INSTANCE,
				new Frame.Result.Failure(type.label(), message, 0)));
		else
			LOGGER.warn("Bridge: a call to '{}' from '{}' failed: {}", call.function(), origin, message);
	}

	private void local(String origin, Frame.Call call, BridgeServer live) {
		SharedFunctions.Entry entry = SharedFunctions.get(call.function());
		if (entry == null) {
			reply(live, origin, call, DiscordError.Type.NO_SUCH_FUNCTION,
				"The bot has no shared function '" + call.function() + "'");
			return;
		}
		CompletionStage<JsonElement> stage;
		try {
			stage = entry.handler().handle(call.args());
		} catch (RuntimeException e) {
			stage = CompletableFuture.failedFuture(e);
		}
		if (stage == null)
			stage = CompletableFuture.completedFuture(JsonNull.INSTANCE);
		stage.whenComplete((data, error) -> {
			if (error != null)
				reply(live, origin, call, DiscordError.Type.ERROR, DiscordError.describe(unwrap(error)));
			else if (call.waiting())
				live.send(origin, new Frame.CallResult(call.id(), data == null ? JsonNull.INSTANCE : data, null));
		});
	}

	private void relay(String origin, Frame.Call call, String target, BridgeServer live) {
		if (!live.isConnected(target)) {
			reply(live, origin, call, DiscordError.Type.OFFLINE, "The server '" + target + "' is offline right now");
			return;
		}
		String id = UUID.randomUUID().toString();
		if (call.waiting())
			relays.put(id, new Relay(origin, call.id(), target));
		if (!live.send(target, new Frame.Call(id, call.function(), call.args(), call.waiting()))) {
			relays.remove(id);
			reply(live, origin, call, DiscordError.Type.OFFLINE, "The server '" + target + "' is offline right now");
			return;
		}
		if (call.waiting()) {
			timers.schedule(() -> {
				Relay lost = relays.remove(id);
				if (lost != null)
					live.send(origin, new Frame.CallResult(lost.originId(), JsonNull.INSTANCE, new Frame.Result.Failure(
						DiscordError.Type.TIMED_OUT.label(), "The server '" + target + "' didn't answer '"
						+ call.function() + "' in time", 0)));
			}, timeoutMillis, TimeUnit.MILLISECONDS);
		}
	}

	private void fanOut(String origin, Frame.Call call, BridgeServer live) {
		if (!call.waiting()) {
			for (String name : live.connectedServers())
				live.send(name, new Frame.Call(UUID.randomUUID().toString(), call.function(), call.args(), false));
			return;
		}
		askAll(call.function(), call.args()).whenComplete((outcomes, error) -> live.send(origin,
			new Frame.CallResult(call.id(), error == null ? SharedValues.outcomes(outcomes) : JsonNull.INSTANCE,
				error == null ? null : new Frame.Result.Failure(DiscordError.Type.ERROR.label(),
					DiscordError.describe(unwrap(error)), 0))));
	}

	void result(String name, Frame.CallResult frame) {
		Ask ask = asks.get(frame.id());
		if (ask != null) {
			if (!ask.server().equals(name) || !asks.remove(frame.id(), ask))
				return;
			if (frame.error() != null) {
				DiscordError.Type type = DiscordError.Type.ofLabel(frame.error().type());
				ask.future().completeExceptionally(new DiscordException(new DiscordError(
					type == null ? DiscordError.Type.ERROR : type, frame.error().message(), null, frame.error().code(),
					frame.error().scope())));
			} else {
				ask.future().complete(frame.data() == null ? JsonNull.INSTANCE : frame.data());
			}
			return;
		}
		Relay relay = relays.get(frame.id());
		if (relay == null || !relay.target().equals(name) || !relays.remove(frame.id(), relay))
			return;
		BridgeServer live = server.get();
		if (live != null)
			live.send(relay.origin(), new Frame.CallResult(relay.originId(), frame.data(), frame.error()));
	}

	void disconnected(String name) {
		BridgeServer live = server.get();
		for (Map.Entry<String, Ask> entry : asks.entrySet()) {
			if (entry.getValue().server().equals(name) && asks.remove(entry.getKey(), entry.getValue()))
				entry.getValue().future().completeExceptionally(failure(DiscordError.Type.OFFLINE,
					"The server '" + name + "' disconnected before it answered"));
		}
		for (Map.Entry<String, Relay> entry : relays.entrySet()) {
			Relay lost = entry.getValue();
			if (lost.target().equals(name) && relays.remove(entry.getKey(), lost) && live != null)
				live.send(lost.origin(), new Frame.CallResult(lost.originId(), JsonNull.INSTANCE, new Frame.Result.Failure(
					DiscordError.Type.OFFLINE.label(), "The server '" + name + "' disconnected before it answered", 0)));
		}
	}
}
