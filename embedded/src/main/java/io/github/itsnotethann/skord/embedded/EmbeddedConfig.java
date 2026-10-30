package io.github.itsnotethann.skord.embedded;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.dv8tion.jda.api.requests.GatewayIntent;

public record EmbeddedConfig(String token, EnumSet<GatewayIntent> intents, long devGuild) {

	public static final String TOKEN_ENV = "SKORD_TOKEN";

	public static EmbeddedConfig of(Map<?, ?> values, Map<String, String> environment) {
		String token = environment.get(TOKEN_ENV);
		if (token == null || token.isBlank())
			token = values.get("token") == null ? "" : String.valueOf(values.get("token")).trim();
		if (token.isEmpty())
			throw new IllegalArgumentException("embedded mode needs a bot token: set 'token:' in the config or "
				+ TOKEN_ENV + " in the environment");
		EnumSet<GatewayIntent> intents = EnumSet.noneOf(GatewayIntent.class);
		if (values.get("intents") instanceof List<?> list) {
			for (Object name : list) {
				try {
					intents.add(GatewayIntent.valueOf(String.valueOf(name).trim().toUpperCase(Locale.ROOT).replace('-', '_')));
				} catch (IllegalArgumentException e) {
					throw new IllegalArgumentException("Unknown intent '" + name + "'");
				}
			}
		} else {
			intents.addAll(EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES,
				GatewayIntent.MESSAGE_CONTENT));
		}
		long guild = 0;
		Object dev = values.get("dev-guild");
		if (dev != null && !String.valueOf(dev).isBlank())
			guild = Long.parseLong(String.valueOf(dev).trim());
		return new EmbeddedConfig(token, intents, guild);
	}

	@Override
	public String toString() {
		return "EmbeddedConfig[token=<hidden>, intents=" + intents + ", devGuild=" + devGuild + "]";
	}
}
