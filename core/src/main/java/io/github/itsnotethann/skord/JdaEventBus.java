package io.github.itsnotethann.skord;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import ch.njol.skript.effects.Delay;
import ch.njol.skript.lang.Trigger;
import io.github.itsnotethann.skord.bridge.BridgeRouter;
import io.github.itsnotethann.skord.command.CommandRegistry;
import io.github.itsnotethann.skord.discord.DiscordEvent;
import io.github.itsnotethann.skord.discord.DiscordEvents;
import io.github.itsnotethann.skord.discord.EventSpec;
import io.github.itsnotethann.skord.event.InteractionEvent;
import io.github.itsnotethann.skord.event.MessageEvent;
import io.github.itsnotethann.skord.event.ReadyEvent;
import io.github.itsnotethann.skord.event.SkordEvent;
import io.github.itsnotethann.skord.event.SlashEvent;
import io.github.itsnotethann.skord.jda.JdaRefs;
import net.dv8tion.jda.api.events.GenericEvent;
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

	private final ScriptThread scriptThread;
	private final Map<Class<?>, Channel> channels = new ConcurrentHashMap<>();
	private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
	// Gateway events that arrive before scripts finish loading have no triggers to run; drop them.
	private volatile boolean live;

	public JdaEventBus(ScriptThread scriptThread) {
		this.scriptThread = scriptThread;
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
		SkordEvent wrapped = wrap(event);
		if (wrapped != null)
			fire(wrapped);
	}

	private static @Nullable SkordEvent wrap(GenericEvent event) {
		if (event instanceof MessageReceivedEvent message)
			return JdaRefs.message(message);
		if (event instanceof SlashCommandInteractionEvent slash)
			return JdaRefs.slash(slash);
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
		if (scriptThread.isPrimaryThread())
			dispatch(event);
		else
			scriptThread.execute(() -> dispatch(event));
	}

	private void dispatch(PlatformEvent event) {
		List<String> owners = List.of();
		if (event instanceof SlashEvent slash) {
			owners = CommandRegistry.owners(slash.command());
			Trigger trigger = CommandRegistry.trigger(slash.command());
			if (trigger != null)
				trigger.execute(slash);
		}
		Channel channel = channels.get(event.getClass());
		if (channel != null)
			channel.call(event);
		publish(event);
		// A server's command: the server runs it, and its own 'dispatched' settles the interaction.
		if (!owners.isEmpty() && event instanceof SlashEvent slash) {
			BridgeRouter router = BridgeRouter.current();
			if (router != null) {
				router.forward(owners, slash, Delay.isDelayed(event));
				return;
			}
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
			default -> null;
		};
		if (published != null)
			deliver(published);
	}

	/** Hands a Discord event that has no script-facing {@link SkordEvent} to the subscribers. */
	public void publishRaw(DiscordEvent published) {
		if (scriptThread.isPrimaryThread())
			deliver(published);
		else
			scriptThread.execute(() -> deliver(published));
	}

	private void deliver(DiscordEvent published) {
		for (Subscriber subscriber : subscribers) {
			if (subscriber.spec().kind() != published.kind())
				continue;
			boolean matches = true;
			for (Map.Entry<String, String> filter : subscriber.spec().filters().entrySet())
				matches &= filter.getValue().equals(filterText(published.data().get(filter.getKey())));
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
