package io.github.itsnotethann.skord.syntax;

import java.util.concurrent.CompletionStage;

import ch.njol.skript.Skript;
import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.classes.Parser;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionType;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.ParseContext;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.registrations.EventValues;
import ch.njol.util.Kleenean;
import io.github.itsnotethann.skord.discord.ChannelRef;
import io.github.itsnotethann.skord.discord.EventSpec;
import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MemberRef;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.Modal;
import io.github.itsnotethann.skord.discord.UserRef;
import io.github.itsnotethann.skord.event.ComponentEvent;
import io.github.itsnotethann.skord.event.InteractionEvent;
import io.github.itsnotethann.skord.event.ModalEvent;
import io.github.itsnotethann.skord.event.SlashEvent;
import io.github.itsnotethann.skord.rest.RestEffect;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.PlatformEvent;

/**
 * Modals: {@code a modal with id "x" titled "Y"} builds one, {@code add text input ... to {_modal}} adds inputs,
 * {@code show {_modal} to the user} opens it, and {@code on modal submit "x"} receives it, where
 * {@code value of input "id"} reads an input.
 */
final class ModalSyntax {

	private ModalSyntax() {}

	static void register() {
		Classes.registerClass(new ClassInfo<>(Modal.class, "discordmodal")
			.user("(discord )?modals?")
			.name("Discord Modal")
			.description("A pop-up form with up to five text inputs. Build one with 'a modal with id \"x\" titled \"Y\"', "
				+ "add inputs with 'add text input with id \"x\" labeled \"Y\" to {_modal}', and open it with "
				+ "'show {_modal} to the user'.")
			.parser(new Parser<>() {
				@Override
				public boolean canParse(ParseContext context) {
					return false;
				}

				@Override
				public String toString(Modal modal, int flags) {
					return modal.title();
				}

				@Override
				public String toVariableNameString(Modal modal) {
					return "modal:" + modal.id();
				}
			}));
		Skript.registerExpression(ExprModal.class, Modal.class, ExpressionType.COMBINED,
			"[a] modal with id %string% titled %string%");
		Skript.registerEffect(EffAddInput.class,
			"add [a] [(short|paragraph:paragraph)] text input with id %string% labeled %string% "
				+ "[with placeholder %-string%] [with value %-string%] [with min[imum] length %-number%] "
				+ "[with max[imum] length %-number%] [(optional:optional)] to %~discordmodal%");
		Skript.registerEffect(EffShowModal.class,
			RestEffect.rest("show [the] [modal] %discordmodal% [to the user]"),
			RestEffect.rest("open [the] modal %discordmodal%"));
		Skript.registerEvent("Modal Submit", EvtModalSubmit.class, ModalEvent.class,
			"modal submit [%-string%] " + SkordSyntax.HELD, "modal submit starting with %string% " + SkordSyntax.HELD);
		EventValues.registerEventValue(ModalEvent.class, UserRef.class, ModalEvent::user);
		EventValues.registerEventValue(ModalEvent.class, MemberRef.class, ModalEvent::member);
		EventValues.registerEventValue(ModalEvent.class, ChannelRef.class, ModalEvent::channel);
		EventValues.registerEventValue(ModalEvent.class, GuildRef.class, ModalEvent::guild);
		EventValues.registerEventValue(ModalEvent.class, MessageRef.class, ModalEvent::message);
		Skript.registerExpression(ExprInput.class, String.class, ExpressionType.COMBINED,
			"[the] value of [the] (input|field) %string%", "[the] input %string%");
	}

	private static @Nullable String literal(Expression<String> expression) {
		return expression instanceof Literal<String> literal ? literal.getSingle() : null;
	}

	/**
	 * {@code a modal with id "x" titled "Y"}. A title over Discord's 45 characters is a load error when it is
	 * written out, and leaves the modal unset (with a warning) when it is computed.
	 */
	public static final class ExprModal extends SimpleExpression<Modal> {

		private Expression<String> id;
		private Expression<String> title;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			id = (Expression<String>) exprs[0];
			title = (Expression<String>) exprs[1];
			String written = literal(title);
			if (written != null && written.length() > Modal.MAX_TITLE) {
				Skript.error("A modal's title can be at most " + Modal.MAX_TITLE + " characters; this one has "
					+ written.length());
				return false;
			}
			String writtenId = literal(id);
			if (writtenId != null && writtenId.length() > Modal.MAX_ID) {
				Skript.error("A modal's id can be at most " + Modal.MAX_ID + " characters");
				return false;
			}
			return true;
		}

		@Override
		protected Modal @Nullable [] get(PlatformEvent event) {
			String idValue = id.getSingle(event);
			String titleValue = title.getSingle(event);
			if (idValue == null || titleValue == null || idValue.isEmpty() || titleValue.isEmpty())
				return null;
			if (titleValue.length() > Modal.MAX_TITLE || idValue.length() > Modal.MAX_ID) {
				Skript.warning("Modal '" + idValue + "' has a title over " + Modal.MAX_TITLE + " characters or an id over "
					+ Modal.MAX_ID + "; Discord would refuse it, so it was not made");
				return null;
			}
			return new Modal[] {new Modal(idValue, titleValue)};
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends Modal> getReturnType() {
			return Modal.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "modal with id " + id.toString(event, debug) + " titled " + title.toString(event, debug);
		}
	}

	/**
	 * {@code add [short|paragraph] text input with id "x" labeled "Y" [with placeholder "..."] [with value "..."]
	 * [with min length n] [with max length n] [optional] to {_modal}}. Inputs are required unless marked optional.
	 * The modal is a value, so this sets the variable to the modal with one more input. An input Discord would refuse
	 * (a sixth one, a label over 45 characters, a length outside 1-4000 or a min above the max) is left out with a
	 * warning.
	 */
	public static final class EffAddInput extends Effect {

		private Expression<String> id;
		private Expression<String> label;
		private @Nullable Expression<String> placeholder;
		private @Nullable Expression<String> value;
		private @Nullable Expression<Number> min;
		private @Nullable Expression<Number> max;
		private Expression<Modal> modal;
		private boolean paragraph;
		private boolean optional;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			id = (Expression<String>) exprs[0];
			label = (Expression<String>) exprs[1];
			placeholder = (Expression<String>) exprs[2];
			value = (Expression<String>) exprs[3];
			min = (Expression<Number>) exprs[4];
			max = (Expression<Number>) exprs[5];
			modal = (Expression<Modal>) exprs[6];
			paragraph = parseResult.hasTag("paragraph");
			optional = parseResult.hasTag("optional");
			if (!modal.isSingle()) {
				Skript.error("Text inputs are added to one modal at a time");
				return false;
			}
			String written = literal(label);
			if (written != null && written.length() > Modal.MAX_TITLE) {
				Skript.error("A text input's label can be at most " + Modal.MAX_TITLE + " characters; this one has "
					+ written.length());
				return false;
			}
			return true;
		}

		@Override
		protected void execute(PlatformEvent event) {
			Modal current = modal.getSingle(event);
			String idValue = id.getSingle(event);
			String labelValue = label.getSingle(event);
			if (current == null || idValue == null || labelValue == null || idValue.isEmpty() || labelValue.isEmpty())
				return;
			String why = null;
			if (current.inputs().size() >= Modal.MAX_INPUTS)
				why = "the modal already has " + Modal.MAX_INPUTS + " inputs, Discord's limit";
			else if (labelValue.length() > Modal.MAX_TITLE)
				why = "its label is over " + Modal.MAX_TITLE + " characters";
			else if (idValue.length() > Modal.MAX_ID)
				why = "its id is over " + Modal.MAX_ID + " characters";
			else if (current.inputs().stream().anyMatch(input -> input.id().equals(idValue)))
				why = "the modal already has an input with that id";
			int low = -1;
			int high = -1;
			if (why == null && min != null) {
				Number number = min.getSingle(event);
				if (number != null)
					low = number.intValue();
				if (low < 0 || low > Modal.MAX_LENGTH)
					why = "its min length is outside 0-" + Modal.MAX_LENGTH;
			}
			if (why == null && max != null) {
				Number number = max.getSingle(event);
				if (number != null)
					high = number.intValue();
				if (high < 1 || high > Modal.MAX_LENGTH)
					why = "its max length is outside 1-" + Modal.MAX_LENGTH;
			}
			if (why == null && low >= 0 && high >= 0 && low > high)
				why = "its min length is above its max length";
			String placeholderValue = placeholder == null ? null : placeholder.getSingle(event);
			if (why == null && placeholderValue != null && placeholderValue.length() > Modal.MAX_PLACEHOLDER)
				why = "its placeholder is over " + Modal.MAX_PLACEHOLDER + " characters";
			String preset = value == null ? null : value.getSingle(event);
			if (why == null && preset != null && preset.length() > Modal.MAX_LENGTH)
				why = "its value is over " + Modal.MAX_LENGTH + " characters";
			if (why != null) {
				Skript.warning("Text input '" + idValue + "' was left out of modal '" + current.id() + "': " + why);
				return;
			}
			modal.change(event, new Object[] {current.withInput(new Modal.TextInput(idValue, labelValue, paragraph,
				placeholderValue == null || placeholderValue.isEmpty() ? null : placeholderValue,
				preset == null || preset.isEmpty() ? null : preset, low, high, !optional))}, ChangeMode.SET);
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "add " + (paragraph ? "paragraph" : "short") + " text input with id " + id.toString(event, debug)
				+ " labeled " + label.toString(event, debug) + " to " + modal.toString(event, debug);
		}
	}

	/**
	 * {@code show {_modal} to the user} / {@code open modal {_modal}}. Answers a slash command, a button click or a
	 * selection by opening the modal. It has to be the interaction's first response: after a reply, a deferral or a
	 * wait long enough for skord to defer, it fails into {@code last discord error} with a message saying so.
	 */
	public static final class EffShowModal extends RestEffect {

		private Expression<Modal> modal;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			modal = (Expression<Modal>) exprs[0];
			if (getParser().isCurrentEvent(ModalEvent.class)) {
				Skript.error("A modal submit can't be answered with another modal");
				return false;
			}
			if (!getParser().isCurrentEvent(SlashEvent.class) && !getParser().isCurrentEvent(ComponentEvent.class)) {
				Skript.error("A modal can only be shown in answer to a slash command, a button click or a select menu");
				return false;
			}
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			Modal value = modal.getSingle(event);
			if (value == null || !(event instanceof InteractionEvent interaction))
				return null;
			if (value.inputs().isEmpty()) {
				Skript.warning("Modal '" + value.id() + "' has no text inputs; Discord needs at least one, so it was not shown");
				return null;
			}
			return interaction.interaction().showModal(value);
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "show " + modal.toString(event, debug) + " to the user";
		}
	}

	public static final class EvtModalSubmit extends SkriptEvent implements SpecEvent {

		private @Nullable String id;
		private boolean prefix;

		@Override
		public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult) {
			id = args[0] == null ? null : ((String) args[0].getSingle()).trim();
			prefix = matchedPattern == 1;
			return SkordSyntax.allowHeld(parseResult, EventSpec.Kind.MODAL_SUBMIT);
		}

		@Override
		public boolean check(PlatformEvent event) {
			if (!(event instanceof ModalEvent submit))
				return false;
			if (id == null)
				return true;
			return prefix ? submit.id().startsWith(id) : submit.id().equals(id);
		}

		@Override
		public EventSpec spec() {
			if (id == null)
				return new EventSpec(EventSpec.Kind.MODAL_SUBMIT);
			return new EventSpec(EventSpec.Kind.MODAL_SUBMIT, prefix ? "id-prefix" : "id", id);
		}

		@Override
		public boolean postLoad() {
			EventSpecs.add(spec());
			return super.postLoad();
		}

		@Override
		public void unload() {
			EventSpecs.remove(spec());
			super.unload();
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return id == null ? "modal submit" : "modal submit " + (prefix ? "starting with " : "") + "\"" + id + "\"";
		}
	}

	/**
	 * {@code value of input "details"} / {@code input "details"}: what was typed into that input, by its id. An
	 * optional input left empty, or an id the modal doesn't have, is unset.
	 */
	public static final class ExprInput extends SimpleExpression<String> {

		private Expression<String> id;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			if (!getParser().isCurrentEvent(ModalEvent.class)) {
				Skript.error("'value of input' only works in a modal submit event");
				return false;
			}
			id = (Expression<String>) exprs[0];
			return true;
		}

		@Override
		protected String @Nullable [] get(PlatformEvent event) {
			String key = id.getSingle(event);
			if (key == null || !(event instanceof ModalEvent submit))
				return null;
			String typed = submit.inputs().get(key);
			return typed == null || typed.isEmpty() ? null : new String[] {typed};
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "value of input " + id.toString(event, debug);
		}
	}
}
