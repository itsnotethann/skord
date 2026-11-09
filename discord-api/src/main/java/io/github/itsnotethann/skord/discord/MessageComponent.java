package io.github.itsnotethann.skord.discord;

/**
 * Something under a message that people interact with: a {@link Button} or a {@link SelectMenu}. Buttons share
 * action rows, five to a row; a select menu takes a row of its own. One list carries both, in the order given.
 */
public sealed interface MessageComponent permits Button, SelectMenu {

	/** The id clicks and selections arrive with. Empty for a link button. */
	String id();

	boolean disabled();
}
