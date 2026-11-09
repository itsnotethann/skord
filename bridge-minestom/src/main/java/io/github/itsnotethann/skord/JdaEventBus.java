package io.github.itsnotethann.skord;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

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
import org.bukkit.event.Event;

public final class JdaEventBus implements net.dv8tion.jda.api.hooks.EventListener {

	private record Subscriber(EventSpec spec, Consumer<DiscordEvent> handler) {
	}

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
		private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
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
		announceBoost(member.getString("guild_id"), member.getObject("user").getString("id"), start);
	}

	private void announceBoost(String guildId, String userId, long start) {
		if (boostsSeen.size() > 10_000)
			boostsSeen.clear();
		if (!boostsSeen.add(guildId + ":" + userId + ":" + start))
			return;
		Map<String, Object> data = new HashMap<>();
		data.put("guildId", guildId);
		data.put("userId", userId);
		data.put("startEpoch", start);
		publishRaw(new DiscordEvent(EventSpec.Kind.BOOST_START, data));
	}

	public void sweepBoosters(net.dv8tion.jda.api.JDA jda) {
		for (net.dv8tion.jda.api.entities.Guild guild : jda.getGuilds()) {
			try {
				guild.loadMembers(found -> {
					if (found.getTimeBoosted() != null)
						announceBoost(guild.getId(), found.getUser().getId(), found.getTimeBoosted().toEpochSecond());
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
		return null;
	}

	public void fire(Event event) {
		if (host.onScriptThread())
			dispatch(event);
		else
			host.runOnScriptThread(() -> dispatch(event));
	}

	private void dispatch(Event event) {
		if (event instanceof io.github.itsnotethann.skord.event.ComponentEvent used)
			io.github.itsnotethann.skord.interaction.ClickWaiters.offer(used);
		List<String> owners = List.of();
		if (event instanceof SlashEvent slash) {
			owners = CommandRegistry.owners(slash.command());
			Trigger trigger = CommandRegistry.trigger(slash.command());
			if (trigger != null)
				trigger.execute(slash);
		}
		event.callEvent();
		publish(event);
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

	private void publish(Event event) {
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

}
