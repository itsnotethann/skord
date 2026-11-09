package io.github.itsnotethann.skord.bridge.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.itsnotethann.skord.discord.Embeds;
import io.github.itsnotethann.skord.discord.MessageBody;
import io.github.itsnotethann.skord.discord.MessageComponent;
import io.github.itsnotethann.skord.discord.Components;
import io.github.itsnotethann.skord.discord.CategoryRef;
import io.github.itsnotethann.skord.discord.ChannelRef;
import io.github.itsnotethann.skord.discord.Discord;
import io.github.itsnotethann.skord.discord.DiscordActions;
import io.github.itsnotethann.skord.discord.DiscordError;
import io.github.itsnotethann.skord.discord.DiscordEvent;
import io.github.itsnotethann.skord.discord.DiscordEvents;
import io.github.itsnotethann.skord.discord.DiscordException;
import io.github.itsnotethann.skord.discord.DiscordRef;
import io.github.itsnotethann.skord.discord.EventCompletion;
import io.github.itsnotethann.skord.discord.EventSpec;
import io.github.itsnotethann.skord.discord.FetchProperty;
import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.RefCodec;
import io.github.itsnotethann.skord.discord.MemberRef;
import io.github.itsnotethann.skord.discord.RoleRef;
import io.github.itsnotethann.skord.discord.UserRef;
import io.github.itsnotethann.skord.protocol.CommandSpec;
import io.github.itsnotethann.skord.protocol.Frame;
import org.jetbrains.annotations.Nullable;

public final class RemoteDiscord implements Discord, DiscordActions, DiscordEvents {

	private record Registration(EventSpec spec, Consumer<DiscordEvent> handler) {}

	private final List<Registration> registrations = new CopyOnWriteArrayList<>();
	private volatile @Nullable Bridge bridge;
	private int batching;

	public void attach(Bridge bridge) {
		this.bridge = bridge;
		bridge.onEvent(this::deliver);
		publish();
	}

	public void batch(Runnable changes) {
		synchronized (this) {
			batching++;
		}
		try {
			changes.run();
		} finally {
			synchronized (this) {
				batching--;
			}
			publish();
		}
	}

	private void publish() {
		Bridge live = bridge;
		synchronized (this) {
			if (live == null || batching > 0)
				return;
		}
		List<Frame.EventSpec> specs = new ArrayList<>();
		for (Registration registration : registrations) {
			EventSpec spec = registration.spec();
			JsonObject filters = new JsonObject();
			spec.filters().forEach(filters::addProperty);
			Frame.EventSpec wire = new Frame.EventSpec(spec.kind().wire(), filters, spec.held());
			if (!specs.contains(wire))
				specs.add(wire);
		}
		live.subscribe(specs);
	}

	private CompletionStage<?> deliver(Frame.Event frame) {
		EventSpec.Kind kind = EventSpec.Kind.ofWire(frame.kind());
		if (kind == null)
			return null;
		Map<String, Object> data = new LinkedHashMap<>();
		if (frame.data() != null && frame.data().isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : frame.data().getAsJsonObject().entrySet()) {
				Object value = RefCodec.valueFromJson(entry.getValue());
				if (value != null)
					data.put(entry.getKey(), value);
			}
		}
		EventCompletion completion = new EventCompletion();
		DiscordEvent event = new DiscordEvent(kind, data, frame.id(), frame.redelivery(), completion);
		try {
			run(event, kind, data);
		} finally {
			completion.end();
		}
		return completion.stage();
	}

	private void run(DiscordEvent event, EventSpec.Kind kind, Map<String, Object> data) {
		for (Registration registration : registrations) {
			if (registration.spec().kind() != kind)
				continue;
			boolean matches = true;
			for (Map.Entry<String, String> filter : registration.spec().filters().entrySet()) {
				Object value = data.get(filter.getKey());
				String text = value instanceof DiscordRef ref ? Long.toString(ref.id()) : String.valueOf(value);
				matches &= filter.getValue().equals(text);
			}
			if (matches)
				registration.handler().accept(event);
		}
	}

	@Override
	public DiscordActions actions() {
		return this;
	}

	@Override
	public DiscordEvents events() {
		return this;
	}

	@Override
	public Subscription subscribe(EventSpec spec, Consumer<DiscordEvent> handler) {
		Registration registration = new Registration(spec, handler);
		registrations.add(registration);
		publish();
		return () -> {
			if (registrations.remove(registration))
				publish();
		};
	}

	@Override
	public UserRef self() {
		Bridge live = bridge;
		Frame.BotInfo bot = live == null ? null : live.bot();
		if (bot == null)
			return new UserRef(0, null);
		try {
			return new UserRef(Long.parseLong(bot.id()), bot.name());
		} catch (NumberFormatException e) {
			return new UserRef(0, bot.name());
		}
	}

	@Override
	public @Nullable ChannelRef cachedChannel(long id) {
		return new ChannelRef(id);
	}

	@Override
	public @Nullable CategoryRef cachedCategory(long id) {
		return new CategoryRef(id, null);
	}

	@Override
	public @Nullable String cachedName(DiscordRef ref) {
		return ref.name();
	}

	@Override
	public @Nullable Boolean cachedBot(UserRef user) {
		return null;
	}

	@Override
	public long cachedGatewayPing() {
		return 0;
	}

	@Override
	public @Nullable String checkCommand(CommandSpec spec) {
		return null;
	}

	private CompletionStage<Frame.Result> action(String kind, JsonObject args) {
		Bridge live = bridge;
		if (live == null)
			return CompletableFuture.failedFuture(new DiscordException(map(
				new BridgeRequestException("offline", "The bridge is not connected to the bot", 0))));
		return live.action(kind, args).handle((result, error) -> {
			if (error == null)
				return result;
			Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
			throw new DiscordException(map(cause));
		});
	}

	private static DiscordError map(Throwable cause) {
		if (cause instanceof DiscordException failure)
			return failure.error();
		if (cause instanceof BridgeRequestException failure) {
			DiscordError.Type type = DiscordError.Type.ofLabel(failure.errorType());
			if (type == null) {
				type = switch (failure.errorType()) {
					case "offline", "disconnected" -> DiscordError.Type.OFFLINE;
					case "timed out" -> DiscordError.Type.TIMED_OUT;
					default -> DiscordError.Type.OTHER;
				};
			}
			return new DiscordError(type, failure.getMessage(), null, failure.code(), failure.scope());
		}
		return DiscordError.of(cause);
	}

	private static long id(Frame.Result result) {
		try {
			return Long.parseLong(result.message());
		} catch (RuntimeException e) {
			throw new DiscordException(new DiscordError(DiscordError.Type.OTHER,
				"The bot answered without an id", null, 0));
		}
	}

	@Override
	public CompletionStage<MessageRef> send(ChannelRef channel, String text) {
		JsonObject args = new JsonObject();
		args.add("channel", RefCodec.toJson(channel));
		args.addProperty("text", text);
		return action("send", args).thenApply(result -> new MessageRef(id(result), channel.id(), text, text));
	}

	@Override
	public CompletionStage<MessageRef> send(ChannelRef channel, String text, List<? extends MessageComponent> components) {
		if (components.isEmpty())
			return send(channel, text);
		JsonObject args = new JsonObject();
		args.add("channel", RefCodec.toJson(channel));
		args.addProperty("text", text);
		args.add("components", Components.toJson(components));
		return action("send", args).thenApply(result -> new MessageRef(id(result), channel.id(), text, text));
	}

	@Override
	public CompletionStage<MessageRef> reply(MessageRef message, String text) {
		JsonObject args = new JsonObject();
		args.add("message", RefCodec.toJson(message));
		args.addProperty("text", text);
		return action("reply", args).thenApply(result -> new MessageRef(id(result), message.channel(), text, text));
	}

	@Override
	public CompletionStage<MessageRef> edit(MessageRef message, String text) {
		JsonObject args = new JsonObject();
		args.add("message", RefCodec.toJson(message));
		args.addProperty("text", text);
		return action("edit", args).thenApply(result -> new MessageRef(message.id(), message.channel(), text, text));
	}

	/** Text, and embeds, components and mentions when the body sets them: absent fields leave a message's as they are. */
	private static void put(JsonObject args, MessageBody body) {
		args.addProperty("text", body.text());
		if (body.components() != null)
			args.add("components", Components.toJson(body.components()));
		if (body.embeds() != null)
			args.add("embeds", Embeds.toJson(body.embeds()));
		if (body.mentions())
			args.addProperty("mentions", true);
	}

	private static <T> CompletionStage<T> invalid(String problem) {
		return CompletableFuture.failedFuture(new DiscordException(
			new DiscordError(DiscordError.Type.INVALID_INPUT, problem, null, 0)));
	}

	@Override
	public CompletionStage<MessageRef> send(ChannelRef channel, MessageBody body) {
		String problem = body.problem();
		if (problem != null)
			return invalid(problem);
		JsonObject args = new JsonObject();
		args.add("channel", RefCodec.toJson(channel));
		put(args, body);
		return action("send", args).thenApply(result -> new MessageRef(id(result), channel.id(), body.text(), body.text(),
			self(), body.embeds()));
	}

	@Override
	public CompletionStage<MessageRef> reply(MessageRef message, MessageBody body) {
		String problem = body.problem();
		if (problem != null)
			return invalid(problem);
		JsonObject args = new JsonObject();
		args.add("message", RefCodec.toJson(new MessageRef(message.id(), message.channel())));
		put(args, body);
		return action("reply", args).thenApply(result -> new MessageRef(id(result), message.channel(), body.text(),
			body.text(), self(), body.embeds()));
	}

	@Override
	public CompletionStage<MessageRef> edit(MessageRef message, MessageBody body) {
		String problem = body.problem();
		if (problem != null)
			return invalid(problem);
		JsonObject args = new JsonObject();
		args.add("message", RefCodec.toJson(new MessageRef(message.id(), message.channel())));
		put(args, body);
		return action("edit", args).thenApply(result -> message.after(body));
	}

	@Override
	public CompletionStage<Void> delete(MessageRef message) {
		JsonObject args = new JsonObject();
		args.add("message", RefCodec.toJson(new MessageRef(message.id(), message.channel())));
		return action("delete", args).thenApply(result -> null);
	}

	@Override
	public CompletionStage<MessageRef> fetchMessage(ChannelRef channel, long id) {
		JsonObject args = new JsonObject();
		args.add("channel", RefCodec.toJson(channel));
		args.addProperty("id", Long.toString(id));
		return action("fetch-message", args).thenApply(result -> {
			if (RefCodec.fromJson(result.data()) instanceof MessageRef found)
				return found;
			throw new DiscordException(new DiscordError(DiscordError.Type.OTHER, "The bot answered without the message", null, 0));
		});
	}

	@Override
	public CompletionStage<ChannelRef> createTextChannel(CategoryRef category, String name) {
		JsonObject args = new JsonObject();
		args.add("category", RefCodec.toJson(category));
		args.addProperty("name", name);
		return action("create-channel", args).thenApply(result -> new ChannelRef(id(result), name));
	}

	@Override
	public CompletionStage<ChannelRef> createTextChannel(GuildRef guild, String name) {
		JsonObject args = new JsonObject();
		args.add("guild", RefCodec.toJson(guild));
		args.addProperty("name", name);
		return action("create-channel", args).thenApply(result -> new ChannelRef(id(result), name));
	}

	@Override
	public CompletionStage<Void> addRole(MemberRef member, RoleRef role) {
		return memberAction("add-role", member, role, null, 0);
	}

	@Override
	public CompletionStage<Void> removeRole(MemberRef member, RoleRef role) {
		return memberAction("remove-role", member, role, null, 0);
	}

	@Override
	public CompletionStage<Void> kick(MemberRef member, @Nullable String reason) {
		return memberAction("kick", member, null, reason, 0);
	}

	@Override
	public CompletionStage<Void> ban(MemberRef member, @Nullable String reason) {
		return memberAction("ban", member, null, reason, 0);
	}

	@Override
	public CompletionStage<Void> timeout(MemberRef member, long seconds, @Nullable String reason) {
		return memberAction("timeout", member, null, reason, seconds);
	}

	private CompletionStage<Void> memberAction(String kind, MemberRef member, @Nullable RoleRef role, @Nullable String reason,
			long seconds) {
		JsonObject args = new JsonObject();
		args.add("member", RefCodec.toJson(member));
		if (role != null)
			args.add("role", RefCodec.toJson(role));
		if (reason != null)
			args.addProperty("reason", reason);
		if (kind.equals("timeout"))
			args.addProperty("seconds", seconds);
		return action(kind, args).thenApply(result -> null);
	}

	@Override
	public CompletionStage<Object> fetch(DiscordRef ref, FetchProperty property) {
		JsonObject args = new JsonObject();
		args.add("ref", RefCodec.toJson(ref));
		args.addProperty("property", property.name().toLowerCase(Locale.ROOT));
		return action("fetch", args).thenApply(result -> {
			JsonElement data = result.data();
			if (property == FetchProperty.ROLES) {
				List<RoleRef> roles = new ArrayList<>();
				if (data != null && data.isJsonArray()) {
					for (JsonElement entry : data.getAsJsonArray()) {
						if (RefCodec.fromJson(entry) instanceof RoleRef role)
							roles.add(role);
					}
				}
				return roles;
			}
			return RefCodec.valueFromJson(data);
		});
	}

	@Override
	public CompletionStage<Long> gatewayPing() {
		return action("gateway-ping", new JsonObject()).thenApply(result -> {
			Object value = RefCodec.valueFromJson(result.data());
			return value instanceof Number number ? number.longValue() : 0L;
		});
	}
}
