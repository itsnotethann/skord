package io.github.itsnotethann.skord;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.itsnotethann.skord.bridge.BridgeHost;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.yaml.snakeyaml.Yaml;

/**
 * {@code skord.yml}. The token lives here; the {@code SKORD_TOKEN} environment variable overrides it, for hosts
 * that keep secrets out of files.
 */
public record SkordConfig(String token, EnumSet<GatewayIntent> intents, long devGuild, BridgeHost.Settings bridge) {

	public static final String TOKEN_ENV = "SKORD_TOKEN";

	static final String DEFAULT = """
		# skord configuration. After editing, restart skord.

		# Your bot token, from https://discord.com/developers/applications > your app > Bot > Reset Token.
		# Keep this file private: anyone with the token controls your bot.
		# The SKORD_TOKEN environment variable, if set, is used instead of this.
		token: ""

		# Gateway intents: which events Discord sends the bot.
		# message-content is privileged: also turn it on under Bot > Privileged Gateway Intents in the
		# developer portal, or Discord refuses the connection.
		intents:
		  - guild-messages
		  - direct-messages
		  - message-content

		commands:
		  # While developing, put a test guild's ID here: commands then register in that guild only and
		  # update instantly. 0 registers them globally, in every guild and DM.
		  dev-guild: 0

		# The bridge lets game servers connect to this bot (see "sk link"). A port of 0 leaves it off.
		# Put it behind TLS if it is reachable from the internet.
		bridge:
		  port: 0
		  bind: 0.0.0.0
		  # How long, in seconds, a server has to answer a command before the bot tells the user it didn't.
		  timeout: 5
		""";

	/** Writes the default file. Returns false if one already existed. */
	static boolean writeDefault(Path file) throws IOException {
		if (Files.exists(file))
			return false;
		Files.writeString(file, DEFAULT, StandardCharsets.UTF_8);
		return true;
	}

	static SkordConfig load(Path file) throws IOException {
		Map<String, Object> root;
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			root = new Yaml().load(reader);
		}
		if (root == null)
			throw new IllegalStateException(file + " is empty. Delete it and start skord to get a fresh one.");

		String env = System.getenv(TOKEN_ENV);
		String token = env != null && !env.isBlank() ? env : String.valueOf(root.getOrDefault("token", ""));
		// A token never contains whitespace; a pasted one often picks up line breaks from a narrow terminal.
		token = token.replaceAll("\\s+", "");
		if (token.isEmpty() || token.equals("null"))
			throw new IllegalStateException("No bot token. Paste it into token: in " + file.toAbsolutePath()
				+ ", then start skord again.");

		EnumSet<GatewayIntent> intents = EnumSet.noneOf(GatewayIntent.class);
		List<String> unknown = new ArrayList<>();
		if (root.get("intents") instanceof List<?> list) {
			for (Object entry : list) {
				String name = String.valueOf(entry).trim().toUpperCase(Locale.ROOT).replace('-', '_');
				try {
					intents.add(GatewayIntent.valueOf(name));
				} catch (IllegalArgumentException e) {
					unknown.add(String.valueOf(entry));
				}
			}
		}
		if (!unknown.isEmpty())
			throw new IllegalStateException("Unknown intents in " + file + ": " + unknown);
		long devGuild = 0;
		if (root.get("commands") instanceof Map<?, ?> commands && commands.get("dev-guild") != null) {
			String value = String.valueOf(commands.get("dev-guild")).trim();
			if (!value.matches("\\d{1,20}"))
				throw new IllegalStateException("commands.dev-guild in " + file + " must be a guild ID (a number), not '" + value + "'");
			devGuild = Long.parseLong(value);
		}
		BridgeHost.Settings bridge = BridgeHost.Settings.OFF;
		if (root.get("bridge") instanceof Map<?, ?> section) {
			int port = 0;
			if (section.get("port") != null) {
				String value = String.valueOf(section.get("port")).trim();
				if (!value.matches("\\d{1,5}") || Integer.parseInt(value) > 65535)
					throw new IllegalStateException("bridge.port in " + file
						+ " must be a port number (0 turns the bridge off), not '" + value + "'");
				port = Integer.parseInt(value);
			}
			String bind = section.get("bind") == null ? "0.0.0.0" : String.valueOf(section.get("bind")).trim();
			long timeout = BridgeHost.Settings.DEFAULT_TIMEOUT_MILLIS;
			if (section.get("timeout") != null) {
				try {
					timeout = Math.round(Double.parseDouble(String.valueOf(section.get("timeout")).trim()) * 1000);
				} catch (NumberFormatException e) {
					throw new IllegalStateException("bridge.timeout in " + file + " must be a number of seconds, not '"
						+ section.get("timeout") + "'");
				}
				if (timeout < 500)
					throw new IllegalStateException("bridge.timeout in " + file + " must be at least 0.5 seconds");
			}
			int heldMax = BridgeHost.Settings.DEFAULT_HELD_MAX_EVENTS;
			long heldAge = BridgeHost.Settings.DEFAULT_HELD_MAX_AGE_MILLIS;
			if (section.get("held") instanceof Map<?, ?> held) {
				if (held.get("max-events") != null) {
					String value = String.valueOf(held.get("max-events")).trim();
					if (!value.matches("\\d{1,7}") || Integer.parseInt(value) < 1)
						throw new IllegalStateException("bridge.held.max-events in " + file
							+ " must be a whole number of at least 1, not '" + value + "'");
					heldMax = Integer.parseInt(value);
				}
				if (held.get("max-age") != null)
					heldAge = duration(file, "bridge.held.max-age", String.valueOf(held.get("max-age")).trim());
			}
			List<String> order = new ArrayList<>();
			if (section.get("route-order") instanceof List<?> names) {
				for (Object name : names)
					order.add(String.valueOf(name).trim());
			}
			bridge = new BridgeHost.Settings(port, bind, timeout, heldMax, heldAge, List.copyOf(order));
		}
		return new SkordConfig(token, intents, devGuild, bridge);
	}

	private static long duration(Path file, String key, String value) {
		java.util.regex.Matcher match = java.util.regex.Pattern.compile("(\\d+)\\s*([smhd]?)").matcher(value);
		if (!match.matches())
			throw new IllegalStateException(key + " in " + file + " must be a duration such as 90s, 30m, 12h or 7d, not '"
				+ value + "'");
		long amount = Long.parseLong(match.group(1));
		long unit = switch (match.group(2)) {
			case "m" -> 60_000L;
			case "h" -> 3_600_000L;
			case "d" -> 86_400_000L;
			default -> 1_000L;
		};
		return amount * unit;
	}
}
