package io.github.itsnotethann.skord.testbot;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.itsnotethann.skord.ScriptThread;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.EventPriority;
import org.skriptlang.skript.lang.event.PlatformEvent;
import org.skriptlang.skript.platform.EventBus;
import org.skriptlang.skript.platform.EventChannel;
import org.skriptlang.skript.platform.EventListener;
import org.skriptlang.skript.platform.Registration;

/**
 * The event bus of a script host that is not the bot: it only delivers to the handlers scripts registered. It runs
 * no command triggers and settles no interactions, so a server host has to do both itself, as the real ones do.
 */
final class ServerBus implements EventBus {

	private final ScriptThread scriptThread;
	private final Map<Class<?>, Channel> channels = new ConcurrentHashMap<>();

	ServerBus(ScriptThread scriptThread) {
		this.scriptThread = scriptThread;
	}

	@Override
	public @Nullable EventChannel channelFor(Class<? extends PlatformEvent> eventClass) {
		return channels.computeIfAbsent(eventClass, key -> new Channel());
	}

	@Override
	public void fire(PlatformEvent event) {
		if (scriptThread.isPrimaryThread())
			dispatch(event);
		else
			scriptThread.execute(() -> dispatch(event));
	}

	private void dispatch(PlatformEvent event) {
		Channel channel = channels.get(event.getClass());
		if (channel != null)
			channel.call(event);
	}

	private static final class Channel implements EventChannel {

		private final Map<EventPriority, List<EventListener>> listeners = new EnumMap<>(EventPriority.class);

		Channel() {
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
