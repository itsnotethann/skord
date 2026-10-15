package io.github.itsnotethann.skord.bridge;

/**
 * A bridge call that didn't produce an answer. {@link #kind()} is {@code offline}, {@code timed out} or
 * {@code refused}; {@link io.github.itsnotethann.skord.rest.DiscordError#of} turns it into a value a script can
 * branch on, alongside Discord's own errors.
 */
public final class BridgeFailure extends RuntimeException {

	private final String kind;

	public BridgeFailure(String kind, String message) {
		super(message);
		this.kind = kind;
	}

	public String kind() {
		return kind;
	}
}
