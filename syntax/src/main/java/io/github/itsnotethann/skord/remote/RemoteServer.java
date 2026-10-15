package io.github.itsnotethann.skord.remote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import ch.njol.skript.Skript;
import ch.njol.skript.effects.Delay;
import ch.njol.skript.lang.Trigger;
import io.github.itsnotethann.skord.bridge.api.Bridge;
import io.github.itsnotethann.skord.bridge.api.BridgeConfig;
import io.github.itsnotethann.skord.bridge.api.BridgePlatform;
import io.github.itsnotethann.skord.bridge.api.Command;
import io.github.itsnotethann.skord.bridge.api.CommandContext;
import io.github.itsnotethann.skord.bridge.api.RemoteDiscord;
import io.github.itsnotethann.skord.command.CommandRegistry;
import io.github.itsnotethann.skord.discord.CategoryRef;
import io.github.itsnotethann.skord.discord.ChannelRef;
import io.github.itsnotethann.skord.discord.Discord;
import io.github.itsnotethann.skord.discord.DiscordEvent;
import io.github.itsnotethann.skord.discord.DiscordEvents;
import io.github.itsnotethann.skord.discord.EventSpec;
import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MemberRef;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.RoleRef;
import io.github.itsnotethann.skord.discord.UserRef;
import io.github.itsnotethann.skord.event.MessageEvent;
import io.github.itsnotethann.skord.event.ReadyEvent;
import io.github.itsnotethann.skord.event.SlashEvent;
import io.github.itsnotethann.skord.interaction.Interaction;
import io.github.itsnotethann.skord.protocol.CommandSpec;
import io.github.itsnotethann.skord.protocol.Frame;
import io.github.itsnotethann.skord.protocol.Ref;
import io.github.itsnotethann.skord.syntax.EventSpecs;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.PlatformEvent;

/**
 * A script host's side of the bridge: the loaded scripts' commands and event handlers go to the bot, and what the
 * bot sends back runs them. Call {@link #publish} after every load or reload. {@code on ready} fires on every
 * welcome, not once.
 */
public final class RemoteServer {

	private final BridgePlatform platform;
	private final RemoteDiscord discord = new RemoteDiscord();
	private final List<DiscordEvents.Subscription> live = new ArrayList<>();
	private volatile @Nullable Bridge bridge;

	public RemoteServer(BridgePlatform platform) {
		this.platform = platform;
		Discord.install(discord);
	}

	public RemoteDiscord discord() {
		return discord;
	}

	public @Nullable Bridge bridge() {
		return bridge;
	}

	public Bridge start(BridgeConfig config, Bridge.ConnectionListener... extra) {
		List<Bridge.ConnectionListener> listeners = new ArrayList<>();
		listeners.add(new Bridge.ConnectionListener() {
			@Override
			public void onConnect(Frame.BotInfo bot) {
				Skript.eventBus().fire(new ReadyEvent());
			}
		});
		listeners.addAll(List.of(extra));
		Bridge started = Bridge.start(config, platform, listeners.toArray(new Bridge.ConnectionListener[0]));
		bridge = started;
		discord.attach(started);
		return started;
	}

	public void publish() {
		Bridge current = bridge;
		discord.batch(() -> {
			live.forEach(DiscordEvents.Subscription::cancel);
			live.clear();
			for (EventSpec spec : EventSpecs.current())
				live.add(discord.events().subscribe(spec, this::deliver));
		});
		if (current == null)
			return;
		List<Command> commands = new ArrayList<>();
		for (CommandSpec spec : CommandRegistry.specs().values())
			commands.add(new Command(spec, context -> dispatch(spec, context)));
		current.declareCommands(commands);
	}

	private static final Map<EventSpec.Kind, Function<DiscordEvent, PlatformEvent>> FACTORIES =
		new java.util.concurrent.ConcurrentHashMap<>();

	static {
		FACTORIES.put(EventSpec.Kind.MESSAGE, RemoteServer::message);
	}

	public static void factory(EventSpec.Kind kind, Function<DiscordEvent, PlatformEvent> factory) {
		FACTORIES.put(kind, factory);
	}

	private static @Nullable PlatformEvent message(DiscordEvent event) {
		Object message = event.data().get("message");
		Object author = event.data().get("author");
		Object channel = event.data().get("channel");
		Object guild = event.data().get("guild");
		if (!(message instanceof MessageRef messageRef) || !(author instanceof UserRef authorRef)
			|| !(channel instanceof ChannelRef channelRef))
			return null;
		return new MessageEvent(messageRef, authorRef, channelRef, guild instanceof GuildRef guildRef ? guildRef : null);
	}

	private void deliver(DiscordEvent event) {
		Function<DiscordEvent, PlatformEvent> factory = FACTORIES.get(event.kind());
		PlatformEvent made = factory == null ? null : factory.apply(event);
		if (made == null)
			return;
		HandlerTracker.watch(made, event.completion());
		Skript.eventBus().fire(made);
	}

	private @Nullable CompletionStage<?> dispatch(CommandSpec spec, CommandContext context) {
		Interaction interaction = new Interaction("/" + context.command(), new ContextResponder(context));
		Ref guildRef = context.guild();
		long guildId = guildRef == null ? 0 : parse(guildRef.id());
		Map<String, Object> options = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : context.options().entrySet()) {
			Object value = entry.getValue();
			CommandSpec.Option option = spec.option(entry.getKey());
			if (value instanceof Ref ref && option != null) {
				long id = parse(ref.id());
				String name = ref.name().isEmpty() ? null : ref.name();
				value = switch (option.kind()) {
					case USER -> new UserRef(id, name);
					case MEMBER -> new MemberRef(id, guildId, name);
					case ROLE -> new RoleRef(id, name);
					case CHANNEL -> new ChannelRef(id, name);
					case CATEGORY -> new CategoryRef(id, name);
					default -> ref;
				};
			}
			options.put(entry.getKey(), value);
		}
		Ref userRef = context.user();
		Ref memberRef = context.member();
		Ref channelRef = context.channel();
		SlashEvent event = new SlashEvent(context.command(), interaction, options,
			userRef == null ? null : new UserRef(parse(userRef.id()), name(userRef)),
			memberRef == null ? null : new MemberRef(parse(memberRef.id()), guildId, name(memberRef)),
			channelRef == null ? null : new ChannelRef(parse(channelRef.id()), name(channelRef)),
			guildRef == null ? null : new GuildRef(guildId, name(guildRef)));
		Trigger trigger = CommandRegistry.trigger(context.command());
		if (trigger != null)
			trigger.execute(event);
		Skript.eventBus().fire(event);
		boolean paused = Delay.isDelayed(event);
		interaction.afterDispatch(paused);
		return paused ? new CompletableFuture<>() : null;
	}

	private static @Nullable String name(Ref ref) {
		return ref.name().isEmpty() ? null : ref.name();
	}

	private static long parse(String id) {
		try {
			return Long.parseLong(id);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
