package io.github.itsnotethann.skord.discord;

import java.util.function.Consumer;

public interface DiscordEvents {

	Subscription subscribe(EventSpec spec, Consumer<DiscordEvent> handler);

	interface Subscription {

		void cancel();
	}
}
