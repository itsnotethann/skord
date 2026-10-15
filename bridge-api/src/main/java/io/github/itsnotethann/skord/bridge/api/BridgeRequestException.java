package io.github.itsnotethann.skord.bridge.api;

/**
 * A request to the bot failed: the bot reported an error, didn't answer in time, or the connection dropped.
 * {@link #errorType()} is a short machine-readable name, such as {@code unknown message} or {@code timed out}.
 */
public final class BridgeRequestException extends RuntimeException {

	private final String errorType;
	private final int code;
	private final String scope;

	public BridgeRequestException(String errorType, String message, int code) {
		this(errorType, message, code, null);
	}

	public BridgeRequestException(String errorType, String message, int code, String scope) {
		super(message);
		this.errorType = errorType;
		this.code = code;
		this.scope = scope;
	}

	/** For an out of scope failure: the scope word the link lacks. Null otherwise. */
	public String scope() {
		return scope;
	}

	public String errorType() {
		return errorType;
	}

	/** Discord's error code when the failure came from Discord, otherwise 0. */
	public int code() {
		return code;
	}
}
