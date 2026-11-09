package io.github.itsnotethann.skord.interaction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.github.itsnotethann.skord.discord.Button;
import io.github.itsnotethann.skord.discord.SelectMenu;
import io.github.itsnotethann.skord.discord.MessageBody;
import io.github.itsnotethann.skord.discord.MessageComponent;
import io.github.itsnotethann.skord.jda.JdaMessages;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.Responder;
import io.github.itsnotethann.skord.jda.JdaRefs;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IMessageEditCallback;
import net.dv8tion.jda.api.interactions.callbacks.IModalCallback;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import io.github.itsnotethann.skord.discord.Modal;
import org.jetbrains.annotations.Nullable;

/** Answers an interaction through JDA. */
public final class JdaResponder implements Responder {

	// Messages sent through an interaction, by ID, with the hook that sent them. An ephemeral message can only
	// be edited through its hook, so 'edit' looks here first. Hooks expire after 15 minutes; a small LRU is
	// plenty.
	private static final Map<Long, InteractionHook> HOOKED = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, InteractionHook> eldest) {
			return size() > 512;
		}
	};

	private final IReplyCallback callback;
	// What can edit the message a component is on: a click, a selection, or a submit of a modal a component opened.
	private final @Nullable IMessageEditCallback click;
	private final @Nullable Message clicked;

	public JdaResponder(IReplyCallback callback) {
		this.callback = callback;
		this.click = null;
		this.clicked = null;
	}

	/** For a button click or a selection: answers can also update the message the component is on. */
	public JdaResponder(GenericComponentInteractionCreateEvent click) {
		this.callback = click;
		this.click = click;
		this.clicked = click.getMessage();
	}

	/** For a modal submit: answers can update a message only when a component on it opened the modal. */
	public JdaResponder(ModalInteractionEvent submit) {
		this.callback = submit;
		this.click = submit.getMessage() == null ? null : submit;
		this.clicked = submit.getMessage();
	}

	@Override
	public CompletionStage<?> showModal(Modal modal) {
		if (!(callback instanceof IModalCallback opener))
			throw new UnsupportedOperationException("only a slash command, a button click or a selection can show a modal");
		return opener.replyModal(modal(modal)).submit();
	}

	/** A modal as JDA builds it: each text input under its label, in the order given. */
	public static net.dv8tion.jda.api.modals.Modal modal(Modal modal) {
		var builder = net.dv8tion.jda.api.modals.Modal.create(modal.id(), modal.title());
		for (Modal.TextInput input : modal.inputs()) {
			var text = TextInput.create(input.id(), input.paragraph() ? TextInputStyle.PARAGRAPH : TextInputStyle.SHORT)
				.setRequired(input.required());
			if (input.minLength() >= 0)
				text.setMinLength(input.minLength());
			if (input.maxLength() >= 0)
				text.setMaxLength(input.maxLength());
			if (input.placeholder() != null)
				text.setPlaceholder(input.placeholder());
			if (input.value() != null)
				text.setValue(input.value());
			builder.addComponents(Label.of(input.label(), text.build()));
		}
		return builder.build();
	}

	/**
	 * Components as JDA action rows, in the order given: runs of buttons five to a row, and each select menu in a
	 * row of its own. Used for interaction answers and plain sends alike.
	 */
	public static List<ActionRow> rows(List<? extends MessageComponent> components) {
		List<ActionRow> rows = new java.util.ArrayList<>();
		List<net.dv8tion.jda.api.components.buttons.Button> run = new java.util.ArrayList<>();
		for (MessageComponent component : components) {
			switch (component) {
				case Button button -> run.add(button(button));
				case SelectMenu menu -> {
					if (!run.isEmpty()) {
						rows.addAll(ActionRow.partitionOf(run));
						run.clear();
					}
					rows.add(ActionRow.of(menu(menu)));
				}
			}
		}
		if (!run.isEmpty())
			rows.addAll(ActionRow.partitionOf(run));
		return rows;
	}

	private static net.dv8tion.jda.api.components.buttons.Button button(Button button) {
		if (button.isLink())
			return net.dv8tion.jda.api.components.buttons.Button.link(button.url(), button.label())
				.withDisabled(button.disabled());
		ButtonStyle style = switch (button.style()) {
			case PRIMARY -> ButtonStyle.PRIMARY;
			case SECONDARY -> ButtonStyle.SECONDARY;
			case SUCCESS -> ButtonStyle.SUCCESS;
			case DANGER -> ButtonStyle.DANGER;
			case LINK -> ButtonStyle.LINK;
		};
		return net.dv8tion.jda.api.components.buttons.Button.of(style, button.id(), button.label())
			.withDisabled(button.disabled());
	}

	private static net.dv8tion.jda.api.components.selections.StringSelectMenu menu(SelectMenu menu) {
		var builder = net.dv8tion.jda.api.components.selections.StringSelectMenu.create(menu.id());
		for (SelectMenu.Option option : menu.options()) {
			var built = net.dv8tion.jda.api.components.selections.SelectOption.of(option.label(), option.value())
				.withDefault(option.isDefault());
			if (option.description() != null)
				built = built.withDescription(option.description());
			if (option.emoji() != null)
				built = built.withEmoji(net.dv8tion.jda.api.entities.emoji.Emoji.fromFormatted(option.emoji()));
			builder.addOptions(built);
		}
		if (menu.placeholder() != null)
			builder.setPlaceholder(menu.placeholder());
		builder.setRequiredRange(menu.effectiveMin(), menu.effectiveMax());
		builder.setDisabled(menu.disabled());
		return builder.build();
	}

	@Override
	public CompletionStage<@Nullable MessageRef> reply(String text, List<? extends MessageComponent> components, boolean ephemeral,
			boolean wantMessage) {
		return reply(MessageBody.of(text, components), ephemeral, wantMessage);
	}

	@Override
	public CompletionStage<@Nullable MessageRef> editOriginal(String text, List<? extends MessageComponent> components,
			boolean wantMessage) {
		return editOriginal(MessageBody.of(text, components), wantMessage);
	}

	@Override
	public CompletionStage<@Nullable MessageRef> followUp(String text, List<? extends MessageComponent> components, boolean ephemeral,
			boolean wantMessage) {
		return followUp(MessageBody.of(text, components), ephemeral, wantMessage);
	}

	@Override
	public CompletionStage<?> deferUpdate() {
		if (click == null)
			throw new UnsupportedOperationException("only a component click can be updated");
		return click.deferEdit().submit();
	}

	@Override
	public CompletionStage<@Nullable MessageRef> update(String text, @Nullable List<? extends MessageComponent> components,
			boolean wantMessage) {
		return update(MessageBody.of(text, components), wantMessage);
	}

	/** The live hook that can edit the message with this ID, if it was sent through an interaction. */
	public static @Nullable InteractionHook hookFor(long messageId) {
		InteractionHook hook;
		synchronized (HOOKED) {
			hook = HOOKED.get(messageId);
		}
		return hook == null || hook.isExpired() ? null : hook;
	}

	@Override
	public CompletionStage<?> deferReply(boolean ephemeral) {
		return callback.deferReply(ephemeral).submit();
	}

	@Override
	public CompletionStage<@Nullable MessageRef> reply(String text, boolean ephemeral, boolean wantMessage) {
		return reply(MessageBody.of(text), ephemeral, wantMessage);
	}

	@Override
	public CompletionStage<@Nullable MessageRef> editOriginal(String text, boolean wantMessage) {
		return editOriginal(MessageBody.of(text), wantMessage);
	}

	@Override
	public CompletionStage<@Nullable MessageRef> editMessage(String messageId, String text) {
		return editMessage(messageId, MessageBody.of(text));
	}

	@Override
	public CompletionStage<?> deleteOriginal() {
		return callback.getHook().deleteOriginal().submit();
	}

	@Override
	public CompletionStage<@Nullable MessageRef> followUp(String text, boolean ephemeral, boolean wantMessage) {
		return followUp(MessageBody.of(text), ephemeral, wantMessage);
	}

	@Override
	public CompletionStage<@Nullable MessageRef> reply(MessageBody body, boolean ephemeral, boolean wantMessage) {
		return callback.reply(JdaMessages.create(body)).setEphemeral(ephemeral).submit().thenCompose(hook -> {
			if (!wantMessage)
				return CompletableFuture.completedFuture(null);
			// Discord usually returns the message with the response; fetch it only when it didn't.
			if (hook.hasCallbackResponse() && hook.getCallbackResponse().getMessage() != null)
				return CompletableFuture.completedFuture(track(hook.getCallbackResponse().getMessage()));
			return hook.retrieveOriginal().submit().thenApply(this::track);
		});
	}

	@Override
	public CompletionStage<@Nullable MessageRef> editOriginal(MessageBody body, boolean wantMessage) {
		return callback.getHook().editOriginal(JdaMessages.edit(body)).submit().thenApply(this::track);
	}

	@Override
	public CompletionStage<@Nullable MessageRef> editMessage(String messageId, MessageBody body) {
		return callback.getHook().editMessageById(messageId, JdaMessages.edit(body)).submit().thenApply(this::track);
	}

	@Override
	public CompletionStage<@Nullable MessageRef> followUp(MessageBody body, boolean ephemeral, boolean wantMessage) {
		return callback.getHook().sendMessage(JdaMessages.create(body)).setEphemeral(ephemeral).submit()
			.thenApply(this::track);
	}

	@Override
	public CompletionStage<@Nullable MessageRef> update(MessageBody body, boolean wantMessage) {
		if (click == null)
			throw new UnsupportedOperationException("only a component click can be updated");
		return click.editMessage(JdaMessages.edit(body)).submit()
			.thenApply(hook -> wantMessage && clicked != null ? track(clicked).after(body) : null);
	}

	private MessageRef track(Message message) {
		synchronized (HOOKED) {
			HOOKED.put(message.getIdLong(), callback.getHook());
		}
		return JdaRefs.message(message);
	}
}
