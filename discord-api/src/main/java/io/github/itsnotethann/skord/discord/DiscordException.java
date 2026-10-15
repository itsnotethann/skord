package io.github.itsnotethann.skord.discord;

public final class DiscordException extends RuntimeException {

	private final DiscordError error;

	public DiscordException(DiscordError error) {
		super(error.message());
		this.error = error;
	}

	public DiscordError error() {
		return error;
	}
}
