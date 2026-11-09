package io.github.itsnotethann.skord;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

import ch.njol.skript.effects.Delay;
import ch.njol.skript.lang.Trigger;
import io.github.itsnotethann.skord.command.CommandRegistry;
import io.github.itsnotethann.skord.discord.DiscordEvent;
import io.github.itsnotethann.skord.discord.DiscordEvents;
import io.github.itsnotethann.skord.discord.EventSpec;
import io.github.itsnotethann.skord.event.InteractionEvent;
import io.github.itsnotethann.skord.event.MessageEvent;
import io.github.itsnotethann.skord.event.ReadyEvent;
import io.github.itsnotethann.skord.event.SkordEvent;
import io.github.itsnotethann.skord.event.SlashEvent;
import io.github.itsnotethann.skord.jda.JdaHost;
import io.github.itsnotethann.skord.jda.JdaRefs;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.RawGatewayEvent;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.EventPriority;
import org.skriptlang.skript.lang.event.PlatformEvent;
import org.skriptlang.skript.platform.EventBus;
import org.skriptlang.skript.platform.EventChannel;
import org.skriptlang.skript.platform.EventListener;
import org.skriptlang.skript.platform.Registration;

/**
 * Skript's event bus, fed by JDA. JDA delivers on its own threads; this class only wraps the event and
 * hands it to the script thread, so no script code ever runs on a JDA thread.
 * <p>
 * Interaction events get two extra steps on the script thread: a slash command first runs its
 * {@code slash command} structure's trigger, and once every trigger has finished its synchronous walk, the
 * interaction learns whether any of them paused, so it can defer (see {@link io.github.itsnotethann.skord.interaction.Interaction}).
 */
public final class JdaEventBus implements EventBus, net.dv8tion.jda.api.hooks.EventListener {

	private record Subscriber(EventSpec spec, Consumer<DiscordEvent> handler) {
	}

	/** Hands a slash command that a game server owns to that server. Returns false when nobody can take it. */
	public interface Forwarder {
		boolean forward(List<String> owners, SlashEvent slash, boolean delayed);

		/**
		 * Sends a modal submit to the server that showed the modal. False when no server showed it, and the bot's own
		 * handlers settle the submit.
		 */
		default boolean forwardModal(io.github.itsnotethann.skord.event.ModalEvent submit, boolean delayed) {
			return false;
		}

		/**
		 * Sends a click to the server that sent the message it is on. False when no server sent it, and the bot's
		 * own handlers settle the click.
		 */
		default boolean forwardComponent(io.github.itsnotethann.skord.event.ComponentEvent click, boolean delayed) {
			return false;
		}
	}

	private final JdaHost host;
	private volatile @Nullable Forwarder forwarder;
	private volatile @Nullable Supplier<EventBus> delegate;
	private final Map<Class<?>, Channel> channels = new ConcurrentHashMap<>();
	private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
	// Gateway events that arrive before scripts finish loading have no triggers to run; drop them.
	private volatile boolean live;

	static {
		io.github.itsnotethann.skord.discord.HeldKinds.register(EventSpec.Kind.BOOST_START,
			data -> "boost:" + data.get("guildId") + ":" + data.get("userId") + ":" + data.get("startEpoch"));
	}

	public JdaEventBus(JdaHost host) {
		this.host = host;
	}

	public void forwarder(@Nullable Forwarder forwarder) {
		this.forwarder = forwarder;
	}

	/** Sends script-facing events to a host's own bus instead of this one's channels. For a server that runs skord inside it. */
	public void delegate(@Nullable Supplier<EventBus> delegate) {
		this.delegate = delegate;
	}

	public void goLive() {
		live = true;
	}

	public DiscordEvents.Subscription subscribe(EventSpec spec, Consumer<DiscordEvent> handler) {
		Subscriber subscriber = new Subscriber(spec, handler);
		subscribers.add(subscriber);
		return () -> subscribers.remove(subscriber);
	}

	@Override
	public void onEvent(GenericEvent event) {
		if (!live)
			return;
		if (event instanceof RawGatewayEvent raw) {
			if ("GUILD_MEMBER_UPDATE".equals(raw.getType()))
				boostFrom(raw.getPayload());
			return;
		}
		SkordEvent wrapped = wrap(event);
		if (wrapped != null)
			fire(wrapped);
	}

	private final java.util.Set<String> boostsSeen = ConcurrentHashMap.newKeySet();

	private void boostFrom(DataObject member) {
		if (member.isNull("premium_since") || !member.hasKey("guild_id") || member.isNull("user"))
			return;
		long start;
		try {
			start = java.time.OffsetDateTime.parse(member.getString("premium_since")).toEpochSecond();
		} catch (java.time.format.DateTimeParseException e) {
			return;
		}
		announceBoost(member.getString("guild_id"), member.getObject("user").getString("id"), start, true);
	}

	private void announceBoost(String guildId, String userId, long start, boolean live) {
		if (boostsSeen.size() > 10_000)
			boostsSeen.clear();
		if (!boostsSeen.add(guildId + ":" + userId + ":" + start))
			return;
		long guild = Long.parseLong(guildId), user = Long.parseLong(userId);
		io.github.itsnotethann.skord.event.BoostEvent boost = new io.github.itsnotethann.skord.event.BoostEvent(
			new io.github.itsnotethann.skord.discord.MemberRef(user, guild), start,
			io.github.itsnotethann.skord.event.BoostEvent.id(guild, user, start), false);
		if (live)
			fire(boost);
		else
			publishRaw(boostEvent(boost));
	}

	public void sweepBoosters(net.dv8tion.jda.api.JDA jda) {
		for (net.dv8tion.jda.api.entities.Guild guild : jda.getGuilds()) {
			try {
				guild.loadMembers(found -> {
					if (found.getTimeBoosted() != null)
						announceBoost(guild.getId(), found.getUser().getId(), found.getTimeBoosted().toEpochSecond(), false);
				});
			} catch (RuntimeException e) {
				org.slf4j.LoggerFactory.getLogger(JdaEventBus.class)
					.warn("Could not sweep boosters in {}: {}", guild.getName(), e.getMessage());
			}
		}
	}

	private static @Nullable SkordEvent wrap(GenericEvent event) {
		if (event instanceof MessageReceivedEvent message)
			return JdaRefs.message(message);
		if (event instanceof SlashCommandInteractionEvent slash)
			return JdaRefs.slash(slash);
		if (event instanceof net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent button)
			return JdaRefs.button(button);
		if (event instanceof net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent select)
			return JdaRefs.select(select);
		if (event instanceof net.dv8tion.jda.api.events.interaction.ModalInteractionEvent submit)
			return JdaRefs.modal(submit);
		if (event instanceof net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent join)
			return new io.github.itsnotethann.skord.event.MemberEvent.Join(new io.github.itsnotethann.skord.discord.MemberRef(
				join.getUser().getIdLong(), join.getGuild().getIdLong(), join.getUser().getName()));
		if (event instanceof net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent left)
			return new io.github.itsnotethann.skord.event.MemberEvent.Leave(new io.github.itsnotethann.skord.discord.MemberRef(
				left.getUser().getIdLong(), left.getGuild().getIdLong(), left.getUser().getName()));
		return null;
	}

	@Override
	public @Nullable EventChannel channelFor(Class<? extends PlatformEvent> eventClass) {
		if (!SkordEvent.class.isAssignableFrom(eventClass))
			return null;
		return channels.computeIfAbsent(eventClass, Channel::new);
	}

	@Override
	public void fire(PlatformEvent event) {
		if (host.onScriptThread())
			dispatch(event);
		else
			host.runOnScriptThread(() -> dispatch(event));
	}

	private void dispatch(PlatformEvent event) {
		if (event instanceof io.github.itsnotethann.skord.event.ComponentEvent used)
			io.github.itsnotethann.skord.interaction.ClickWaiters.offer(used);
		List<String> owners = List.of();
		if (event instanceof SlashEvent slash) {
			owners = CommandRegistry.owners(slash.command());
			Trigger trigger = CommandRegistry.trigger(slash.command());
			if (trigger != null)
				trigger.execute(slash);
		}
		Supplier<EventBus> outer = delegate;
		if (outer != null) {
			outer.get().fire(event);
		} else {
			Channel channel = channels.get(event.getClass());
			if (channel != null)
				channel.call(event);
		}
		publish(event);
		// A server's command: the server runs it, and its own 'dispatched' settles the interaction.
		if (!owners.isEmpty() && event instanceof SlashEvent slash) {
			Forwarder current = forwarder;
			if (current != null && current.forward(owners, slash, Delay.isDelayed(event)))
				return;
		}
		// A click or selection on a message a server sent: that server handles it, and its 'dispatched' settles the click.
		if (event instanceof io.github.itsnotethann.skord.event.ComponentEvent click) {
			Forwarder current = forwarder;
			if (current != null && current.forwardComponent(click, Delay.isDelayed(event)))
				return;
		}
		// A submit of a modal a server showed: that server handles it, and its 'dispatched' settles the submit.
		if (event instanceof io.github.itsnotethann.skord.event.ModalEvent submit) {
			Forwarder current = forwarder;
			if (current != null && current.forwardModal(submit, Delay.isDelayed(event)))
				return;
		}
		if (event instanceof InteractionEvent interaction)
			interaction.interaction().afterDispatch(Delay.isDelayed(event));
	}

	private void publish(PlatformEvent event) {
		if (subscribers.isEmpty())
			return;
		DiscordEvent published = switch (event) {
			case ReadyEvent ready -> new DiscordEvent(EventSpec.Kind.READY, Map.of());
			case MessageEvent message -> {
				Map<String, Object> data = new HashMap<>();
				data.put("message", message.message());
				data.put("author", message.author());
				data.put("channel", message.channel());
				if (message.guild() != null)
					data.put("guild", message.guild());
				yield new DiscordEvent(EventSpec.Kind.MESSAGE, data);
			}
			case SlashEvent slash -> {
				Map<String, Object> data = new HashMap<>();
				data.put("command", slash.command());
				data.put("options", slash.options());
				if (slash.user() != null)
					data.put("user", slash.user());
				if (slash.member() != null)
					data.put("member", slash.member());
				if (slash.channel() != null)
					data.put("channel", slash.channel());
				if (slash.guild() != null)
					data.put("guild", slash.guild());
				yield new DiscordEvent(EventSpec.Kind.SLASH_COMMAND, data);
			}
			case io.github.itsnotethann.skord.event.ButtonEvent button -> {
				Map<String, Object> data = new HashMap<>();
				data.put("id", button.id());
				if (button.message() != null)
					data.put("message", button.message());
				if (button.user() != null)
					data.put("user", button.user());
				if (button.member() != null)
					data.put("member", button.member());
				if (button.channel() != null)
					data.put("channel", button.channel());
				if (button.guild() != null)
					data.put("guild", button.guild());
				yield new DiscordEvent(EventSpec.Kind.BUTTON_CLICK, data);
			}
			case io.github.itsnotethann.skord.event.SelectEvent select -> {
				Map<String, Object> data = new HashMap<>();
				data.put("id", select.id());
				data.put("values", select.values());
				if (select.message() != null)
					data.put("message", select.message());
				if (select.user() != null)
					data.put("user", select.user());
				if (select.member() != null)
					data.put("member", select.member());
				if (select.channel() != null)
					data.put("channel", select.channel());
				if (select.guild() != null)
					data.put("guild", select.guild());
				yield new DiscordEvent(EventSpec.Kind.SELECT_MENU, data);
			}
			case io.github.itsnotethann.skord.event.MemberEvent member -> {
				Map<String, Object> data = new HashMap<>();
				data.put("member", member.member());
				data.put("user", member.user());
				data.put("guild", member.guild());
				yield new DiscordEvent(member instanceof io.github.itsnotethann.skord.event.MemberEvent.Join
					? EventSpec.Kind.MEMBER_JOIN : EventSpec.Kind.MEMBER_LEAVE, data);
			}
			case io.github.itsnotethann.skord.event.BoostEvent boost -> boostEvent(boost);
			default -> null;
		};
		if (published != null)
			deliver(published);
	}

	private static DiscordEvent boostEvent(io.github.itsnotethann.skord.event.BoostEvent boost) {
		Map<String, Object> data = new HashMap<>();
		data.put("guildId", Long.toString(boost.guild().id()));
		data.put("userId", Long.toString(boost.user().id()));
		data.put("startEpoch", boost.startEpoch());
		return new DiscordEvent(EventSpec.Kind.BOOST_START, data);
	}

	/** Hands a Discord event that has no script-facing {@link SkordEvent} to the subscribers. */
	public void publishRaw(DiscordEvent published) {
		if (host.onScriptThread())
			deliver(published);
		else
			host.runOnScriptThread(() -> deliver(published));
	}

	private void deliver(DiscordEvent published) {
		for (Subscriber subscriber : subscribers) {
			if (subscriber.spec().kind() != published.kind())
				continue;
			boolean matches = true;
			for (Map.Entry<String, String> filter : subscriber.spec().filters().entrySet()) {
				String key = filter.getKey();
				if (key.endsWith("-prefix"))
					matches &= filterText(published.data().get(key.substring(0, key.length() - 7))).startsWith(filter.getValue());
				else
					matches &= filter.getValue().equals(filterText(published.data().get(key)));
			}
			if (matches)
				subscriber.handler().accept(published);
		}
	}

	private static String filterText(@Nullable Object value) {
		return value instanceof io.github.itsnotethann.skord.discord.DiscordRef ref
			? Long.toString(ref.id()) : String.valueOf(value);
	}

	private static final class Channel implements EventChannel {

		private final Map<EventPriority, List<EventListener>> listeners = new EnumMap<>(EventPriority.class);

		Channel(Class<?> eventClass) {
			for (EventPriority priority : EventPriority.values())
				listeners.put(priority, new CopyOnWriteArrayList<>());
		}

		void call(PlatformEvent event) {
			for (EventPriority priority : EventPriority.values()) {
				for (EventListener listener : listeners.get(priority))
					listener.execute(event);
			}
		}

		@Override
		public Registration register(EventPriority priority, EventListener listener) {
			List<EventListener> list = listeners.get(priority);
			list.add(listener);
			return () -> list.remove(listener);
		}
	}
}
