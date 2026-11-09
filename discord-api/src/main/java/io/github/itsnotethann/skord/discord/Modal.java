package io.github.itsnotethann.skord.discord;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * A modal: a pop-up form with a title and up to five text inputs, shown as the first answer to a slash command, a
 * button click or a selection. Its submit arrives as a separate interaction carrying each input's value by id.
 * Immutable; {@link #withInput} returns a copy, which is how scripts change a modal held in a variable.
 */
public record Modal(String id, String title, List<TextInput> inputs) {

	/** Discord's limit on inputs in one modal. */
	public static final int MAX_INPUTS = 5;
	/** Discord's limit on a modal's title and on an input's label. */
	public static final int MAX_TITLE = 45;
	/** Discord's limit on an input's length, and so on its min and max length. */
	public static final int MAX_LENGTH = 4000;
	/** Discord's limit on a placeholder. */
	public static final int MAX_PLACEHOLDER = 100;
	/** Discord's limit on a custom id. */
	public static final int MAX_ID = 100;

	/**
	 * One text input. {@code minLength} and {@code maxLength} are -1 when not set, which leaves Discord's defaults
	 * (0 and 4000). {@code value} pre-fills the input.
	 */
	public record TextInput(String id, String label, boolean paragraph, @Nullable String placeholder,
							@Nullable String value, int minLength, int maxLength, boolean required) {

		public TextInput(String id, String label) {
			this(id, label, false, null, null, -1, -1, true);
		}
	}

	public Modal {
		inputs = List.copyOf(inputs);
	}

	public Modal(String id, String title) {
		this(id, title, List.of());
	}

	public Modal withInput(TextInput input) {
		List<TextInput> more = new ArrayList<>(inputs);
		more.add(input);
		return new Modal(id, title, more);
	}
}
