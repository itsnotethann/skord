package io.github.itsnotethann.skord.discord;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * A string select menu: up to 25 options, of which someone picks between {@code minValues} and {@code maxValues}.
 * It takes a whole action row. Immutable; the {@code with...} methods return a copy, which is how scripts change
 * a menu held in a variable.
 */
public record SelectMenu(String id, @Nullable String placeholder, List<Option> options, int minValues, int maxValues,
						 boolean disabled) implements MessageComponent {

	/** Discord's limit on options in one menu. */
	public static final int MAX_OPTIONS = 25;

	/** One choice. {@code value} is what a selection reports; {@code label} is what people read. */
	public record Option(String label, String value, @Nullable String description, @Nullable String emoji,
						 boolean isDefault) {

		public Option(String label, String value) {
			this(label, value, null, null, false);
		}
	}

	public SelectMenu {
		options = List.copyOf(options);
	}

	public SelectMenu(String id, @Nullable String placeholder) {
		this(id, placeholder, List.of(), 1, 1, false);
	}

	public SelectMenu withOption(Option option) {
		List<Option> more = new ArrayList<>(options);
		more.add(option);
		return new SelectMenu(id, placeholder, more, minValues, maxValues, disabled);
	}

	public SelectMenu withRange(int min, int max) {
		return new SelectMenu(id, placeholder, options, min, max, disabled);
	}

	public SelectMenu withDisabled(boolean value) {
		return new SelectMenu(id, placeholder, options, minValues, maxValues, value);
	}

	/**
	 * The most picks Discord will accept for this menu: at least 1, at most the option count. A script that asks
	 * for 3 of 2 options gets 2 rather than a rejected message.
	 */
	public int effectiveMax() {
		return Math.max(1, Math.min(maxValues, Math.max(1, options.size())));
	}

	/** The fewest picks: 0 or more, no higher than {@link #effectiveMax()}. */
	public int effectiveMin() {
		return Math.max(0, Math.min(minValues, effectiveMax()));
	}
}
