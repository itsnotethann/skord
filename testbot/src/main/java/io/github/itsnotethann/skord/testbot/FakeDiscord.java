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
import io.github.itsnotethann.skord.discord.MessageBody;
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
		return send(channel, MessageBody.of(text));
	}

	@Override
	public CompletionStage<MessageRef> send(ChannelRef channel, String text,
			java.util.List<? extends io.github.itsnotethann.skord.discord.MessageComponent> components) {
		return send(channel, MessageBody.of(text, components.isEmpty() ? null : components));
	}

	@Override
	public CompletionStage<MessageRef> send(ChannelRef channel, MessageBody body) {
		called("send");
		if (!KNOWN.contains(channel.id()))
			return gone(DiscordError.Type.UNKNOWN_CHANNEL, "Unknown Channel", 10003);
		String problem = body.problem();
		if (problem != null)
			return gone(DiscordError.Type.INVALID_INPUT, problem, 0);
		MessageRef sent = new MessageRef(NEXT_ID.incrementAndGet(), channel.id(), body.text(), body.text(), self(),
			body.embeds() == null ? List.of() : body.embeds());
		stored(sent);
		VIEWS.put(sent.id(), "send: " + describe(body, false));
		if (body.hasComponents() || body.hasEmbeds()) {
			lastSent = sent;
			lastShown = (body.text() + parts(body, false)).strip();
		}
		return CompletableFuture.completedFuture(sent);
	}

	private static volatile @Nullable MessageRef lastSent;
	private static volatile String lastShown = "";
	// What the fake last did to each message through the port, by id: "send: ...", "edit: ...", "deleted".
	private static final Map<Long, String> VIEWS = new ConcurrentHashMap<>();
	// Messages the fake has sent, by id, so a fetch finds them and an edit keeps what it doesn't change. Deleted ones
	// are remembered too: an edit, fetch or delete of one is Discord's unknown message.
	private static final Map<Long, MessageRef> MESSAGES = new ConcurrentHashMap<>();
	private static final Set<Long> DELETED = ConcurrentHashMap.newKeySet();

	private static void stored(MessageRef message) {
		MESSAGES.put(message.id(), message);
	}

	/** The last message sent with buttons, from any process: the one a test then clicks on. */
	static @Nullable MessageRef lastSent() {
		return lastSent;
	}

	/** That message as text plus its buttons, the way responder lines show them. */
	static String lastShown() {
		return lastShown;
	}

	/**
	 * What the fake last did to this message through the port (not an interaction answer), from any process, with
	 * every part it set and whether its mentions ping: {@code send: Hi <@7> mentions off}, {@code edit: ...},
	 * {@code deleted}. Kept per message, so scripts running at the same time don't read each other's.
	 */
	static @Nullable String view(long messageId) {
		return VIEWS.get(messageId);
	}

	/** Text, then components and embeds as responder lines show them, then whether mentions ping. */
	private static String describe(MessageBody body, boolean edit) {
		return (body.text() + parts(body, edit)).strip() + (body.mentions() ? " mentions on" : " mentions off");
	}

	/**
	 * Components and embeds as responder lines show them. For a new message only the parts it has show; for an edit
	 * every part it sets shows, so removing one reads as {@code [no embeds]}.
	 */
	static String parts(MessageBody body, boolean edit) {
		StringBuilder out = new StringBuilder();
		if (edit ? body.components() != null : body.hasComponents())
			out.append(' ').append(io.github.itsnotethann.skord.discord.Components.shown(body.components()));
		if (edit ? body.embeds() != null : body.hasEmbeds())
			out.append(' ').append(io.github.itsnotethann.skord.discord.Embeds.shown(body.embeds()));
		return out.toString();
	}

	@Override
	public CompletionStage<MessageRef> reply(MessageRef message, String text) {
		return reply(message, MessageBody.of(text));
	}

	@Override
	public CompletionStage<MessageRef> reply(MessageRef message, MessageBody body) {
		called("reply");
		if (!KNOWN.contains(message.channel()))
			return gone(DiscordError.Type.UNKNOWN_CHANNEL, "Unknown Channel", 10003);
		String problem = body.problem();
		if (problem != null)
			return gone(DiscordError.Type.INVALID_INPUT, problem, 0);
		MessageRef sent = new MessageRef(NEXT_ID.incrementAndGet(), message.channel(), body.text(), body.text(), self(),
			body.embeds() == null ? List.of() : body.embeds());
		stored(sent);
		VIEWS.put(sent.id(), "reply: " + describe(body, false));
		return CompletableFuture.completedFuture(sent);
	}

	@Override
	public CompletionStage<MessageRef> edit(MessageRef message, String text) {
		return edit(message, MessageBody.of(text));
	}

	@Override
	public CompletionStage<MessageRef> edit(MessageRef message, MessageBody body) {
		called("edit");
		if (!KNOWN.contains(message.channel()))
			return gone(DiscordError.Type.UNKNOWN_CHANNEL, "Unknown Channel", 10003);
		if (DELETED.contains(message.id()))
			return gone(DiscordError.Type.UNKNOWN_MESSAGE, "Unknown Message", 10008);
		String problem = body.problem();
		if (problem != null)
			return gone(DiscordError.Type.INVALID_INPUT, problem, 0);
		MessageRef before = MESSAGES.getOrDefault(message.id(), message);
		MessageRef edited = before.after(body);
		stored(edited);
		VIEWS.put(message.id(), "edit: " + describe(body, true));
		return CompletableFuture.completedFuture(edited);
	}

	@Override
	public CompletionStage<Void> delete(MessageRef message) {
		called("delete");
		if (!KNOWN.contains(message.channel()))
			return gone(DiscordError.Type.UNKNOWN_CHANNEL, "Unknown Channel", 10003);
		if (MESSAGES.remove(message.id()) == null)
			return gone(DiscordError.Type.UNKNOWN_MESSAGE, "Unknown Message", 10008);
		DELETED.add(message.id());
		VIEWS.put(message.id(), "deleted");
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletionStage<MessageRef> fetchMessage(ChannelRef channel, long id) {
		called("fetch-message");
		if (!KNOWN.contains(channel.id()))
			return gone(DiscordError.Type.UNKNOWN_CHANNEL, "Unknown Channel", 10003);
		MessageRef found = MESSAGES.get(id);
		if (found == null || found.channel() != channel.id())
			return gone(DiscordError.Type.UNKNOWN_MESSAGE, "Unknown Message", 10008);
		return CompletableFuture.completedFuture(found);
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

	static final List<String> MODERATION = new java.util.concurrent.CopyOnWriteArrayList<>();

	@Override
	public CompletionStage<Void> addRole(MemberRef member, RoleRef role) {
		return moderate("add-role", member, role, "give " + role.id() + " to " + member.id());
	}

	@Override
	public CompletionStage<Void> removeRole(MemberRef member, RoleRef role) {
		return moderate("remove-role", member, role, "take " + role.id() + " from " + member.id());
	}

	@Override
	public CompletionStage<Void> kick(MemberRef member, @Nullable String reason) {
		return moderate("kick", member, null, "kick " + member.id() + (reason == null ? "" : " because " + reason));
	}

	@Override
	public CompletionStage<Void> ban(MemberRef member, @Nullable String reason) {
		return moderate("ban", member, null, "ban " + member.id() + (reason == null ? "" : " because " + reason));
	}

	@Override
	public CompletionStage<Void> timeout(MemberRef member, long seconds, @Nullable String reason) {
		return moderate("timeout", member, null, (seconds == 0 ? "untimeout " + member.id() : "timeout " + member.id()
			+ " for " + seconds + "s") + (reason == null ? "" : " because " + reason));
	}

	private CompletionStage<Void> moderate(String kind, MemberRef member, @Nullable RoleRef role, String line) {
		called(kind);
		if (!KNOWN.contains(member.guild()))
			return gone(DiscordError.Type.MISSING_ACCESS, "Missing Access", 50001);
		if (!KNOWN.contains(member.id()))
			return gone(DiscordError.Type.UNKNOWN_MEMBER, "Unknown Member", 10007);
		if (role != null && role.id() == 666666666666666666L)
			return gone(DiscordError.Type.HIERARCHY, "Can't modify a role with higher or equal highest role than yourself!", 0);
		if (role != null && !KNOWN.contains(role.id()))
			return gone(DiscordError.Type.UNKNOWN_ROLE, "Unknown Role", 10011);
		MODERATION.add(line);
		return CompletableFuture.completedFuture(null);
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

			@Override
			public CompletionStage<@Nullable MessageRef> reply(String text, java.util.List<? extends io.github.itsnotethann.skord.discord.MessageComponent> components,
					boolean ephemeral, boolean wantMessage) {
				TestBot.recorder().mark(script, "reply" + (ephemeral ? " ephemerally" : "") + ": " + text + shown(components));
				MessageRef sent = new MessageRef(NEXT_ID.incrementAndGet(), 111111111111111111L, text, text);
				lastSent = sent;
				lastShown = text + shown(components);
				return CompletableFuture.completedFuture(sent);
			}

			@Override
			public CompletionStage<@Nullable MessageRef> editOriginal(String text,
					java.util.List<? extends io.github.itsnotethann.skord.discord.MessageComponent> components, boolean wantMessage) {
				return record("edit original: " + text + shown(components));
			}

			@Override
			public CompletionStage<@Nullable MessageRef> followUp(String text,
					java.util.List<? extends io.github.itsnotethann.skord.discord.MessageComponent> components, boolean ephemeral, boolean wantMessage) {
				return record("follow-up" + (ephemeral ? " ephemerally" : "") + ": " + text + shown(components));
			}

			@Override
			public CompletionStage<?> deferUpdate() {
				return record("defer update");
			}

			@Override
			public CompletionStage<@Nullable MessageRef> update(String text,
					java.util.List<? extends io.github.itsnotethann.skord.discord.MessageComponent> components, boolean wantMessage) {
				return record("update: " + text + (components == null ? "" : shown(components)));
			}

			@Override
			public CompletionStage<?> showModal(io.github.itsnotethann.skord.discord.Modal modal) {
				return record("modal: " + io.github.itsnotethann.skord.discord.Modals.shown(modal));
			}

			@Override
			public CompletionStage<@Nullable MessageRef> reply(MessageBody body, boolean ephemeral, boolean wantMessage) {
				String line = "reply" + (ephemeral ? " ephemerally" : "") + ": " + content(body, false) + pings(body);
				if (!body.hasComponents() && !body.hasEmbeds())
					return record(line);
				TestBot.recorder().mark(script, line);
				MessageRef sent = new MessageRef(NEXT_ID.incrementAndGet(), 111111111111111111L, body.text(), body.text(),
					new UserRef(999999999999999999L, "skord-test"), body.embeds());
				stored(sent);
				lastSent = sent;
				lastShown = content(body, false);
				return CompletableFuture.completedFuture(sent);
			}

			@Override
			public CompletionStage<@Nullable MessageRef> editOriginal(MessageBody body, boolean wantMessage) {
				return record("edit original: " + content(body, true) + pings(body));
			}

			@Override
			public CompletionStage<@Nullable MessageRef> editMessage(String messageId, MessageBody body) {
				return record("edit message " + messageId + ": " + content(body, true) + pings(body));
			}

			@Override
			public CompletionStage<@Nullable MessageRef> followUp(MessageBody body, boolean ephemeral, boolean wantMessage) {
				return record("follow-up" + (ephemeral ? " ephemerally" : "") + ": " + content(body, false) + pings(body));
			}

			@Override
			public CompletionStage<@Nullable MessageRef> update(MessageBody body, boolean wantMessage) {
				return record("update: " + content(body, true) + pings(body));
			}

			/** The text and then the parts; with no text, only the parts. */
			private String content(MessageBody body, boolean edit) {
				return body.text().isEmpty() ? parts(body, edit).strip() : body.text() + parts(body, edit);
			}

			/** Shown only when mentions ping, so lines from before mentions read as they did. */
			private String pings(MessageBody body) {
				return body.mentions() ? " (mentions)" : "";
			}

			private String shown(java.util.List<? extends io.github.itsnotethann.skord.discord.MessageComponent> components) {
				return " " + io.github.itsnotethann.skord.discord.Components.shown(components);
			}

			private <T> CompletionStage<@Nullable T> record(String request) {
				TestBot.recorder().mark(script, request);
				return CompletableFuture.completedFuture(null);
			}
		};
	}
}
