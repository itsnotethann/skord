package io.github.itsnotethann.skord.discord;

import org.jetbrains.annotations.Nullable;

/**
 * A button under a message. A link button has a {@code url} and no id worth matching (its id is empty): Discord
 * opens the link and sends no click.
 */
public record Button(String id, String label, Style style, boolean disabled, @Nullable String url)
	implements MessageComponent {

	public enum Style {
		PRIMARY, SECONDARY, SUCCESS, DANGER, LINK;

		public static Style ofWord(String word) {
			return valueOf(word.toUpperCase(java.util.Locale.ROOT));
		}
	}

	public Button(String id, String label, Style style, boolean disabled) {
		this(id, label, style, disabled, null);
	}

	public Button(String id, String label, Style style) {
		this(id, label, style, false, null);
	}

	public static Button link(String url, String label, boolean disabled) {
		return new Button("", label, Style.LINK, disabled, url);
	}

	public boolean isLink() {
		return style == Style.LINK;
	}

	public Button withDisabled(boolean value) {
		return new Button(id, label, style, value, url);
	}
}
