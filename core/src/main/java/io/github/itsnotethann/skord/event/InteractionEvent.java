package io.github.itsnotethann.skord.event;

import io.github.itsnotethann.skord.interaction.Interaction;

/**
 * An event Discord expects an answer to. After its triggers run, the bus hands the outcome to
 * {@link Interaction#afterDispatch}, which defers or warns as needed.
 */
public abstract class InteractionEvent extends SkordEvent {

	private final Interaction interaction;

	protected InteractionEvent(Interaction interaction) {
		this.interaction = interaction;
	}

	public Interaction interaction() {
		return interaction;
	}
}
