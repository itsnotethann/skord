package io.github.itsnotethann.skord.testbot;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import io.github.itsnotethann.skord.command.CommandSync;
import io.github.itsnotethann.skord.command.JdaSpecs;
import io.github.itsnotethann.skord.discord.CategoryRef;
import io.github.itsnotethann.skord.discord.ChannelRef;
import io.github.itsnotethann.skord.discord.Discord;
import io.github.itsnotethann.skord.discord.DiscordActions;
import io.github.itsnotethann.skord.discord.DiscordError;
import io.github.itsnotethann.skord.discord.DiscordEvent;
import io.github.itsnotethann.skord.discord.DiscordEvents;
import io.github.itsnotethann.skord.discord.DiscordException;
import io.github.itsnotethann.skord.discord.DiscordRef;
import io.github.itsnotethann.skord.discord.EventSpec;
import io.github.itsnotethann.skord.discord.FetchProperty;
import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MemberRef;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.Responder;
import io.github.itsnotethann.skord.discord.RoleRef;
import io.github.itsnotethann.skord.discord.UserRef;
import io.github.itsnotethann.skord.protocol.CommandSpec;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.Response;
import org.jetbrains.annotations.Nullable;

/**
 * Discord, as far as skord's port reaches. Interaction responses are recorded against the script that fired the
 * interaction; plain requests are futures a script completes or fails by name; command updates are counted.
 */
final class FakeDiscord implements Discord, DiscordActions, DiscordEvents {

	static final FakeDiscord INSTANCE = new FakeDiscord();

	private static final Set<Long> KNOWN = Set.of(111111111111111111L, 222222222222222222L, 333333333333333333L,
		444444444444444444L, 555555555555555555L, 666666666666666666L, 999999999999999999L);
	private static final Map<String, CompletableFuture<Object>> REQUESTS = new ConcurrentHashMap<>();
	private static final AtomicInteger COMMAND_REQUESTS = new AtomicInteger();
	private static final AtomicLong NEXT_ID = new AtomicLong(800000000000000000L);
	private static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();
	private static volatile List<String> synced = List.of();
	private static volatile io.github.itsnotethann.skord.JdaEventBus bus;

	private FakeDiscord() {}

	/**
	 * The request called {@code id}. Whichever side comes first creates it, so a script may complete a
	 * request before the trigger that makes it has got there.
	 */
	static CompletableFuture<Object> request(String id) {
		return REQUESTS.computeIfAbsent(id, key -> new CompletableFuture<>());
	}

	/** Stands in for Discord's error responses, whose message is what 'last discord error' shows. */
	static final class FakeError extends RuntimeException {
		FakeError(String message) {
			super(message);
		}
	}

	/** What JDA throws when its own check finds a permission missing: it names the permission. */
	static final class FakePermissionError extends PermissionException {
		FakePermissionError(Permission permission) {
			super(permission, "Cannot perform action due to a lack of Permission. Missing permission: " + permission);
		}
	}

	/** Discord's own error response with this code, as JDA reports it. */
	static ErrorResponseException response(int code) {
		return ErrorResponseException.create(ErrorResponse.fromCode(code), new Response(new Exception("fake"), Set.of()));
	}

	static io.github.itsnotethann.skord.JdaEventBus bus() {
		return bus;
	}

	static void bus(io.github.itsnotethann.skord.JdaEventBus bus) {
		FakeDiscord.bus = bus;
	}

	/** How many requests of this kind (send, reply, edit, create-channel, fetch, ping) reached the fake. */
	static int calls(String kind) {
		AtomicInteger count = CALLS.get(kind);
		return count == null ? 0 : count.get();
	}

	private static void called(String kind) {
		CALLS.computeIfAbsent(kind, key -> new AtomicInteger()).incrementAndGet();
	}

	static int commandRequests() {
		return COMMAND_REQUESTS.get();
	}

	static List<String> syncedCommands() {
		return synced;
	}

	static CommandSync.Sink commandSink() {
		return (guildId, commands) -> {
			COMMAND_REQUESTS.incrementAndGet();
			synced = commands.stream().map(CommandData::getName).sorted().toList();
			return CompletableFuture.completedFuture(null);
		};
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
		return bus.subscribe(spec, handler);
	}

	@Override
	public UserRef self() {
		return new UserRef(999999999999999999L, "skord-test");
	}

	@Override
	public @Nullable ChannelRef cachedChannel(long id) {
		return KNOWN.contains(id) ? new ChannelRef(id, "fake-channel") : null;
	}

	@Override
	public @Nullable CategoryRef cachedCategory(long id) {
		return KNOWN.contains(id) ? new CategoryRef(id, "fake-category") : null;
	}

	@Override
	public @Nullable String cachedName(DiscordRef ref) {
		return KNOWN.contains(ref.id()) ? "fake-" + ref.id() : null;
	}

	@Override
	public @Nullable Boolean cachedBot(UserRef user) {
		return KNOWN.contains(user.id()) ? user.id() == self().id() : null;
	}

	@Override
	public long cachedGatewayPing() {
		return 42;
	}

	@Override
	public @Nullable String checkCommand(CommandSpec spec) {
		return JdaSpecs.problem(spec);
	}

	@Override
	public CompletionStage<MessageRef> send(ChannelRef channel, String text) {
		called("send");
		if (!KNOWN.contains(channel.id()))
			return gone(DiscordError.Type.UNKNOWN_CHANNEL, "Unknown Channel", 10003);
		return CompletableFuture.completedFuture(new MessageRef(NEXT_ID.incrementAndGet(), channel.id(), text, text));
	}

	@Override
	public CompletionStage<MessageRef> reply(MessageRef message, String text) {
		called("reply");
		if (!KNOWN.contains(message.channel()))
			return gone(DiscordError.Type.UNKNOWN_CHANNEL, "Unknown Channel", 10003);
		return CompletableFuture.completedFuture(new MessageRef(NEXT_ID.incrementAndGet(), message.channel(), text, text));
	}

	@Override
	public CompletionStage<MessageRef> edit(MessageRef message, String text) {
		called("edit");
		if (!KNOWN.contains(message.channel()))
			return gone(DiscordError.Type.UNKNOWN_CHANNEL, "Unknown Channel", 10003);
		return CompletableFuture.completedFuture(new MessageRef(message.id(), message.channel(), text, text));
	}

	@Override
	public CompletionStage<ChannelRef> createTextChannel(CategoryRef category, String name) {
		called("create-channel");
		if (!KNOWN.contains(category.id()))
			return gone(DiscordError.Type.UNKNOWN_CHANNEL, "Unknown Channel", 10003);
		return CompletableFuture.completedFuture(new ChannelRef(NEXT_ID.incrementAndGet(), name));
	}

	@Override
	public CompletionStage<ChannelRef> createTextChannel(GuildRef guild, String name) {
		called("create-channel");
		if (!KNOWN.contains(guild.id()))
			return gone(DiscordError.Type.MISSING_ACCESS, "Missing Access", 50001);
		return CompletableFuture.completedFuture(new ChannelRef(NEXT_ID.incrementAndGet(), name));
	}

	@Override
	public CompletionStage<Long> gatewayPing() {
		called("ping");
		return CompletableFuture.completedFuture(42L);
	}

	@Override
	public CompletionStage<Object> fetch(DiscordRef ref, FetchProperty property) {
		called("fetch");
		if (!KNOWN.contains(ref.id())) {
			return switch (ref) {
				case UserRef user -> gone(DiscordError.Type.UNKNOWN_USER, "Unknown User", 10013);
				case MemberRef member -> gone(DiscordError.Type.UNKNOWN_MEMBER, "Unknown Member", 10007);
				case RoleRef role -> gone(DiscordError.Type.UNKNOWN_ROLE, "Unknown Role", 10011);
				case MessageRef message -> gone(DiscordError.Type.UNKNOWN_MESSAGE, "Unknown Message", 10008);
				case GuildRef guild -> gone(DiscordError.Type.MISSING_ACCESS, "Missing Access", 50001);
				default -> gone(DiscordError.Type.UNKNOWN_CHANNEL, "Unknown Channel", 10003);
			};
		}
		Object value = switch (property) {
			case NAME -> "fake-" + ref.id();
			case CONTENT -> "fake content";
			case ROLES -> List.of(new RoleRef(555555555555555555L, "fake-role"));
			case AVATAR_URL -> "https://fake.invalid/avatar/" + ref.id();
			case BOT -> false;
		};
		return CompletableFuture.completedFuture(value);
	}

	private static <T> CompletionStage<T> gone(DiscordError.Type type, String message, int code) {
		return CompletableFuture.failedFuture(new DiscordException(new DiscordError(type, message, null, code)));
	}

	/** Answers every request at once, and records it under {@code script}. */
	static Responder responder(String script) {
		return new Responder() {
			@Override
			public CompletionStage<?> deferReply(boolean ephemeral) {
				return record(ephemeral ? "defer ephemerally" : "defer");
			}

			@Override
			public CompletionStage<@Nullable MessageRef> reply(String text, boolean ephemeral, boolean wantMessage) {
				return record("reply" + (ephemeral ? " ephemerally" : "") + ": " + text);
			}

			@Override
			public CompletionStage<@Nullable MessageRef> editOriginal(String text, boolean wantMessage) {
				return record("edit original: " + text);
			}

			@Override
			public CompletionStage<@Nullable MessageRef> editMessage(String messageId, String text) {
				return record("edit message " + messageId + ": " + text);
			}

			@Override
			public CompletionStage<?> deleteOriginal() {
				return record("delete original");
			}

			@Override
			public CompletionStage<@Nullable MessageRef> followUp(String text, boolean ephemeral, boolean wantMessage) {
				return record("follow-up" + (ephemeral ? " ephemerally" : "") + ": " + text);
			}

			private <T> CompletionStage<@Nullable T> record(String request) {
				TestBot.recorder().mark(script, request);
				return CompletableFuture.completedFuture(null);
			}
		};
	}
}
