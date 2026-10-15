package io.github.itsnotethann.skord.discord;

import java.util.Map;

public record EventSpec(Kind kind, Map<String, String> filters, boolean held) {

	public enum Kind {
		READY,
		MESSAGE,
		SLASH_COMMAND,
		TEST_BOOST;

		public String wire() {
			return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
		}

		public static @org.jetbrains.annotations.Nullable Kind ofWire(String wire) {
			for (Kind kind : values()) {
				if (kind.wire().equals(wire))
					return kind;
			}
			return null;
		}

		public @org.jetbrains.annotations.Nullable io.github.itsnotethann.skord.protocol.Listen listen() {
			return this == MESSAGE ? io.github.itsnotethann.skord.protocol.Listen.MESSAGES : null;
		}
	}

	public EventSpec(Kind kind) {
		this(kind, Map.of(), false);
	}

	public EventSpec(Kind kind, String key, String value) {
		this(kind, Map.of(key, value), false);
	}
}
