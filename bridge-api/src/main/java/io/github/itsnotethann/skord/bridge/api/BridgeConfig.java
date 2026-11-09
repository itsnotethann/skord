package io.github.itsnotethann.skord.bridge.api;

import java.net.URI;
import java.util.Map;

/**
 * {@code bridge.yml}, parsed. The platform reads the file with whatever YAML it has and hands the map to
 * {@link #of}, so the API itself carries no YAML dependency.
 *
 * @param url           where the bot listens, {@code ws://host:port} (or {@code wss://} behind TLS)
 * @param name          this server's name, as given to {@code sk link create}
 * @param secret        what {@code sk link create} printed. Never logged: see {@link #toString()}.
 * @param timeoutMillis how long a request waits for its answer
 */
public record BridgeConfig(URI url, String name, String secret, long timeoutMillis, Map<String, String> meta) {

	public static final long DEFAULT_TIMEOUT_MILLIS = 5_000;

	public BridgeConfig {
		meta = meta == null ? Map.of() : Map.copyOf(meta);
	}

	public BridgeConfig(URI url, String name, String secret, long timeoutMillis) {
		this(url, name, secret, timeoutMillis, Map.of());
	}

	public BridgeConfig withMeta(Map<String, String> more) {
		Map<String, String> merged = new java.util.LinkedHashMap<>(meta);
		merged.putAll(more);
		return new BridgeConfig(url, name, secret, timeoutMillis, merged);
	}

	/** Reads the keys {@code url}, {@code name}, {@code secret} and optionally {@code timeout} (seconds). */
	public static BridgeConfig of(Map<?, ?> values) {
		String url = text(values, "url");
		URI uri = URI.create(url);
		if (!"ws".equals(uri.getScheme()) && !"wss".equals(uri.getScheme()) || uri.getHost() == null)
			throw new IllegalArgumentException("bridge url must look like ws://host:port, not '" + url + "'");
		long timeout = DEFAULT_TIMEOUT_MILLIS;
		Object seconds = values.get("timeout");
		if (seconds != null)
			timeout = Math.round(Double.parseDouble(String.valueOf(seconds)) * 1000);
		Map<String, String> meta = new java.util.LinkedHashMap<>();
		if (values.get("meta") instanceof Map<?, ?> entries) {
			for (Map.Entry<?, ?> entry : entries.entrySet()) {
				if (entry.getKey() != null && entry.getValue() != null)
					meta.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
			}
		}
		return new BridgeConfig(uri, text(values, "name"), text(values, "secret"), timeout, meta);
	}

	private static String text(Map<?, ?> values, String key) {
		Object value = values.get(key);
		if (value == null || String.valueOf(value).isBlank())
			throw new IllegalArgumentException("bridge config is missing '" + key + "'");
		return String.valueOf(value).trim();
	}

	@Override
	public String toString() {
		return "BridgeConfig[url=" + url + ", name=" + name + ", secret=<hidden>, timeoutMillis=" + timeoutMillis + "]";
	}
}
