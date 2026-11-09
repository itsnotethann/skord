package io.github.itsnotethann.skord.syntax;

import java.util.ArrayList;
import java.util.List;
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
import ch.njol.skript.util.Timespan;
import ch.njol.util.Kleenean;
import io.github.itsnotethann.skord.discord.Button;
import io.github.itsnotethann.skord.discord.MessageComponent;
import io.github.itsnotethann.skord.discord.SelectMenu;
import io.github.itsnotethann.skord.discord.ChannelRef;
import io.github.itsnotethann.skord.discord.EventSpec;
import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MemberRef;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.UserRef;
import io.github.itsnotethann.skord.event.ButtonEvent;
import io.github.itsnotethann.skord.event.ComponentEvent;
import io.github.itsnotethann.skord.event.SelectEvent;
import io.github.itsnotethann.skord.interaction.ClickWaiters;
import io.github.itsnotethann.skord.rest.RestEffect;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.PlatformEvent;

final class ComponentSyntax {

	private ComponentSyntax() {}

	static void register() {
		Classes.registerClass(new ClassInfo<>(MessageComponent.class, "discordcomponent")
			.user("(discord )?components?")
			.name("Discord Component")
			.description("A button or a select menu, to put under a message with 'with components'.")
			.parser(new Parser<>() {
				@Override
				public boolean canParse(ParseContext context) {
					return false;
				}

				@Override
				public String toString(MessageComponent component, int flags) {
					return component instanceof Button button ? button.label() : component.id();
				}

				@Override
				public String toVariableNameString(MessageComponent component) {
					return "component:" + component.id();
				}
			}));
		Classes.registerClass(new ClassInfo<>(Button.class, "discordbutton")
			.user("(discord )?buttons?")
			.name("Discord Button")
			.description("A button to put under a message. Build one with 'a button with id \"x\" labeled \"Y\"'.")
			.parser(new Parser<>() {
				@Override
				public boolean canParse(ParseContext context) {
					return false;
				}

				@Override
				public String toString(Button button, int flags) {
					return button.label();
				}

				@Override
				public String toVariableNameString(Button button) {
					return button.isLink() ? "button:" + button.url() : "button:" + button.id();
				}
			}));
		Classes.registerClass(new ClassInfo<>(SelectMenu.class, "discordselectmenu")
			.user("(discord )?select ?menus?")
			.name("Discord Select Menu")
			.description("A menu of options to put under a message. Build one with 'a select menu with id X', then "
				+ "add options to it with 'add option \"Label\" with value \"v\" to {_menu}'.")
			.parser(new Parser<>() {
				@Override
				public boolean canParse(ParseContext context) {
					return false;
				}

				@Override
				public String toString(SelectMenu menu, int flags) {
					return menu.placeholder() != null ? menu.placeholder() : "select menu " + menu.id();
				}

				@Override
				public String toVariableNameString(SelectMenu menu) {
					return "selectmenu:" + menu.id();
				}
			}));
		Skript.registerExpression(ExprButton.class, Button.class, ExpressionType.COMBINED,
			"[a[n]] [(primary:primary|secondary:secondary|success:success|danger:danger)] [(disabled:disabled)] button with id %string% labeled %string%");
		Skript.registerExpression(ExprLinkButton.class, Button.class, ExpressionType.COMBINED,
			"[a] [(disabled:disabled)] link button to %string% labeled %string%");
		Skript.registerExpression(ExprSelectMenu.class, SelectMenu.class, ExpressionType.COMBINED,
			"[a] [(disabled:disabled)] select menu with id %string% [with placeholder %-string%] "
				+ "[allowing (up to %-number%|%-number% to %-number%) choice[s]]");
		Skript.registerEffect(EffAddOption.class,
			"add [a] [(default:default)] option %string% with value %string% [described as %-string%] "
				+ "[with emoji %-string%] to %~discordselectmenu%");
		Skript.registerExpression(ExprComponentId.class, String.class, ExpressionType.SIMPLE, "[the] component id",
			"[the] component id parts");
		Skript.registerExpression(ExprSelectedValues.class, String.class, ExpressionType.SIMPLE,
			"[the] selected values", "[the] selected value");
		Skript.registerEffect(EffWaitClick.class,
			RestEffect.rest("wait for [a] button click on %discordmessage% [by %-discorduser%] within %timespan%"),
			RestEffect.rest("wait for [a] selection on %discordmessage% [by %-discorduser%] within %timespan%"));
		Skript.registerEvent("Button Click", EvtButtonClick.class, ButtonEvent.class,
			"button click [%-string%] " + SkordSyntax.HELD, "button click starting with %string% " + SkordSyntax.HELD);
		Skript.registerEvent("Select Menu", EvtSelectMenu.class, SelectEvent.class,
			"select menu [%-string%] " + SkordSyntax.HELD, "select menu starting with %string% " + SkordSyntax.HELD);
		for (Class<? extends ComponentEvent> used : List.of(ButtonEvent.class, SelectEvent.class)) {
			EventValues.registerEventValue(used, UserRef.class, ComponentEvent::user);
			EventValues.registerEventValue(used, MemberRef.class, ComponentEvent::member);
			EventValues.registerEventValue(used, ChannelRef.class, ComponentEvent::channel);
			EventValues.registerEventValue(used, GuildRef.class, ComponentEvent::guild);
			EventValues.registerEventValue(used, MessageRef.class, ComponentEvent::message);
		}
		Skript.registerEffect(EffUpdate.class,
			RestEffect.rest("edit [the] message to %string% " + Outgoing.TAIL),
			RestEffect.rest("edit [the] message to %string% " + Outgoing.SWAPPED),
			RestEffect.rest("edit [the] message to " + Outgoing.EMBEDS_ONLY));
	}

	public static final class ExprButton extends SimpleExpression<Button> {

		private Expression<String> id;
		private Expression<String> label;
		private Button.Style style = Button.Style.PRIMARY;
		private boolean disabled;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			id = (Expression<String>) exprs[0];
			label = (Expression<String>) exprs[1];
			if (parseResult.hasTag("secondary"))
				style = Button.Style.SECONDARY;
			else if (parseResult.hasTag("success"))
				style = Button.Style.SUCCESS;
			else if (parseResult.hasTag("danger"))
				style = Button.Style.DANGER;
			disabled = parseResult.hasTag("disabled");
			return true;
		}

		@Override
		protected Button @Nullable [] get(PlatformEvent event) {
			String idValue = id.getSingle(event);
			String labelValue = label.getSingle(event);
			if (idValue == null || labelValue == null || idValue.isEmpty() || labelValue.isEmpty())
				return null;
			return new Button[] {new Button(idValue, labelValue, style, disabled)};
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends Button> getReturnType() {
			return Button.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return style.name().toLowerCase() + " button with id " + id.toString(event, debug) + " labeled "
				+ label.toString(event, debug);
		}
	}

	/**
	 * {@code component id} is the clicked component's id; {@code component id parts} is that id split on
	 * {@code :}, so state kept in the id ({@code shop:buy:42}) reads as {@code {_parts::3}}.
	 */
	public static final class ExprComponentId extends SimpleExpression<String> {

		private boolean parts;

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			if (!getParser().isCurrentEvent(ComponentEvent.class, io.github.itsnotethann.skord.event.ModalEvent.class)) {
				Skript.error("'component id' only works in a button click, a select menu or a modal submit event");
				return false;
			}
			parts = matchedPattern == 1;
			return true;
		}

		@Override
		protected String @Nullable [] get(PlatformEvent event) {
			String id = switch (event) {
				case ComponentEvent used -> used.id();
				case io.github.itsnotethann.skord.event.ModalEvent submit -> submit.id();
				default -> null;
			};
			if (id == null)
				return null;
			return parts ? id.split(":", -1) : new String[] {id};
		}

		@Override
		public boolean isSingle() {
			return !parts;
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return parts ? "component id parts" : "component id";
		}
	}

	public static final class ExprLinkButton extends SimpleExpression<Button> {

		private Expression<String> url;
		private Expression<String> label;
		private boolean disabled;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			url = (Expression<String>) exprs[0];
			label = (Expression<String>) exprs[1];
			disabled = parseResult.hasTag("disabled");
			return true;
		}

		@Override
		protected Button @Nullable [] get(PlatformEvent event) {
			String urlValue = url.getSingle(event);
			String labelValue = label.getSingle(event);
			if (urlValue == null || labelValue == null || labelValue.isEmpty()
				|| !(urlValue.startsWith("https://") || urlValue.startsWith("http://")))
				return null;
			return new Button[] {Button.link(urlValue, labelValue, disabled)};
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends Button> getReturnType() {
			return Button.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "link button to " + url.toString(event, debug) + " labeled " + label.toString(event, debug);
		}
	}

	/**
	 * Pauses until someone (or the given user) clicks a button on the message, and stores the button's id; or, for
	 * {@code wait for a selection}, picks from a select menu on it, and stores the picked values. When the time runs
	 * out the store target is left unset and no error is raised. The click or selection still runs its handlers; if
	 * none answers it, it is acknowledged without changing the message.
	 */
	public static final class EffWaitClick extends RestEffect {

		private boolean selection;
		private Expression<MessageRef> message;
		private @Nullable Expression<UserRef> user;
		private Expression<Timespan> within;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			selection = matchedPattern == 1;
			message = (Expression<MessageRef>) exprs[0];
			user = (Expression<UserRef>) exprs[1];
			within = (Expression<Timespan>) exprs[2];
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			MessageRef target = message.getSingle(event);
			Timespan time = within.getSingle(event);
			if (target == null || time == null)
				return null;
			UserRef by = user == null ? null : user.getSingle(event);
			if (user != null && by == null)
				return null;
			long millis = Math.max(1, time.getAs(Timespan.TimePeriod.MILLISECOND));
			return selection ? ClickWaiters.awaitSelection(target.id(), by == null ? 0 : by.id(), millis)
				: ClickWaiters.await(target.id(), by == null ? 0 : by.id(), millis);
		}

		@Override
		protected Class<?> resultType() {
			return String.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "wait for a " + (selection ? "selection" : "button click") + " on " + message.toString(event, debug) + " within "
				+ within.toString(event, debug);
		}
	}

	public static final class EvtButtonClick extends SkriptEvent implements SpecEvent {

		private @Nullable String id;
		private boolean prefix;

		@Override
		public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult) {
			id = args[0] == null ? null : ((String) args[0].getSingle()).trim();
			prefix = matchedPattern == 1;
			return SkordSyntax.allowHeld(parseResult, EventSpec.Kind.BUTTON_CLICK);
		}

		@Override
		public boolean check(PlatformEvent event) {
			if (!(event instanceof ButtonEvent button))
				return false;
			if (id == null)
				return true;
			return prefix ? button.id().startsWith(id) : button.id().equals(id);
		}

		@Override
		public EventSpec spec() {
			if (id == null)
				return new EventSpec(EventSpec.Kind.BUTTON_CLICK);
			return new EventSpec(EventSpec.Kind.BUTTON_CLICK, prefix ? "id-prefix" : "id", id);
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
			return id == null ? "button click" : "button click " + (prefix ? "starting with " : "") + "\"" + id + "\"";
		}
	}

	/**
	 * {@code a select menu with id "x" [with placeholder "..."] [allowing up to 3 choices | allowing 0 to 3
	 * choices]}. Without a range, one option is picked. Options are added afterwards with {@link EffAddOption}, as
	 * a menu's options are usually built in a loop.
	 */
	public static final class ExprSelectMenu extends SimpleExpression<SelectMenu> {

		private Expression<String> id;
		private @Nullable Expression<String> placeholder;
		private @Nullable Expression<Number> upTo;
		private @Nullable Expression<Number> min;
		private @Nullable Expression<Number> max;
		private boolean disabled;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			id = (Expression<String>) exprs[0];
			placeholder = (Expression<String>) exprs[1];
			upTo = (Expression<Number>) exprs[2];
			min = (Expression<Number>) exprs[3];
			max = (Expression<Number>) exprs[4];
			disabled = parseResult.hasTag("disabled");
			return true;
		}

		@Override
		protected SelectMenu @Nullable [] get(PlatformEvent event) {
			String idValue = id.getSingle(event);
			if (idValue == null || idValue.isEmpty())
				return null;
			String placeholderValue = placeholder == null ? null : placeholder.getSingle(event);
			int low = 1;
			int high = 1;
			if (upTo != null) {
				Number value = upTo.getSingle(event);
				if (value == null)
					return null;
				high = value.intValue();
			} else if (min != null && max != null) {
				Number lowValue = min.getSingle(event);
				Number highValue = max.getSingle(event);
				if (lowValue == null || highValue == null)
					return null;
				low = lowValue.intValue();
				high = highValue.intValue();
			}
			high = Math.clamp(high, 1, SelectMenu.MAX_OPTIONS);
			low = Math.clamp(low, 0, high);
			return new SelectMenu[] {new SelectMenu(idValue,
				placeholderValue == null || placeholderValue.isEmpty() ? null : placeholderValue, List.of(), low, high,
				disabled)};
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends SelectMenu> getReturnType() {
			return SelectMenu.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return (disabled ? "disabled " : "") + "select menu with id " + id.toString(event, debug)
				+ (placeholder == null ? "" : " with placeholder " + placeholder.toString(event, debug));
		}
	}

	/**
	 * {@code add [default] option "Label" with value "v" [described as "..."] [with emoji "..."] to {_menu}}. The
	 * menu is a value, so this sets the variable to the menu with one more option. Past Discord's 25 options the
	 * option is left out with a warning.
	 */
	public static final class EffAddOption extends Effect {

		private Expression<String> label;
		private Expression<String> value;
		private @Nullable Expression<String> description;
		private @Nullable Expression<String> emoji;
		private Expression<SelectMenu> menu;
		private boolean isDefault;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			label = (Expression<String>) exprs[0];
			value = (Expression<String>) exprs[1];
			description = (Expression<String>) exprs[2];
			emoji = (Expression<String>) exprs[3];
			menu = (Expression<SelectMenu>) exprs[4];
			isDefault = parseResult.hasTag("default");
			if (!menu.isSingle()) {
				Skript.error("Options are added to one select menu at a time");
				return false;
			}
			return true;
		}

		@Override
		protected void execute(PlatformEvent event) {
			SelectMenu current = menu.getSingle(event);
			String labelValue = label.getSingle(event);
			String valueValue = value.getSingle(event);
			if (current == null || labelValue == null || valueValue == null || labelValue.isEmpty()
				|| valueValue.isEmpty())
				return;
			if (current.options().size() >= SelectMenu.MAX_OPTIONS) {
				Skript.warning("Select menu '" + current.id() + "' already has " + SelectMenu.MAX_OPTIONS
					+ " options, Discord's limit; '" + labelValue + "' was left out");
				return;
			}
			String descriptionValue = description == null ? null : description.getSingle(event);
			String emojiValue = emoji == null ? null : emoji.getSingle(event);
			menu.change(event, new Object[] {current.withOption(new SelectMenu.Option(labelValue, valueValue,
				descriptionValue == null || descriptionValue.isEmpty() ? null : descriptionValue,
				emojiValue == null || emojiValue.isEmpty() ? null : emojiValue, isDefault))}, ChangeMode.SET);
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "add option " + label.toString(event, debug) + " with value " + value.toString(event, debug)
				+ " to " + menu.toString(event, debug);
		}
	}

	/** The values picked in a select menu event, in the order Discord sent them; empty when none were picked. */
	public static final class ExprSelectedValues extends SimpleExpression<String> {

		private boolean single;

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			if (!getParser().isCurrentEvent(SelectEvent.class)) {
				Skript.error("'selected values' only works in a select menu event");
				return false;
			}
			single = matchedPattern == 1;
			return true;
		}

		@Override
		protected String @Nullable [] get(PlatformEvent event) {
			if (!(event instanceof SelectEvent select))
				return null;
			if (single)
				return select.values().isEmpty() ? null : new String[] {select.values().getFirst()};
			return select.values().toArray(String[]::new);
		}

		@Override
		public boolean isSingle() {
			return single;
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return single ? "selected value" : "selected values";
		}
	}

	public static final class EvtSelectMenu extends SkriptEvent implements SpecEvent {

		private @Nullable String id;
		private boolean prefix;

		@Override
		public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult) {
			id = args[0] == null ? null : ((String) args[0].getSingle()).trim();
			prefix = matchedPattern == 1;
			return SkordSyntax.allowHeld(parseResult, EventSpec.Kind.SELECT_MENU);
		}

		@Override
		public boolean check(PlatformEvent event) {
			if (!(event instanceof SelectEvent select))
				return false;
			if (id == null)
				return true;
			return prefix ? select.id().startsWith(id) : select.id().equals(id);
		}

		@Override
		public EventSpec spec() {
			if (id == null)
				return new EventSpec(EventSpec.Kind.SELECT_MENU);
			return new EventSpec(EventSpec.Kind.SELECT_MENU, prefix ? "id-prefix" : "id", id);
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
			return id == null ? "select menu" : "select menu " + (prefix ? "starting with " : "") + "\"" + id + "\"";
		}
	}

	/**
	 * {@code edit the message to ...} answers a click, a selection or a submit by editing the message the component is
	 * on: its text, and its embeds and components where named ({@code with no embeds} removes them). With
	 * {@code edit the message to embed {_page} ...} the text is removed.
	 */
	public static final class EffUpdate extends RestEffect {

		private Outgoing message;

		@Override
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			if (!getParser().isCurrentEvent(ComponentEvent.class, io.github.itsnotethann.skord.event.ModalEvent.class)) {
				Skript.error("'edit the message' only works in a button click, a select menu or a modal submit event; use 'edit <message> to' elsewhere");
				return false;
			}
			message = Outgoing.parse(exprs, 0, switch (matchedPattern) {
				case 0 -> Outgoing.Form.TEXT;
				case 1 -> Outgoing.Form.SWAPPED;
				default -> Outgoing.Form.EMBEDS;
			}, parseResult);
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			// A modal submit edits the message whose component opened the modal; Interaction refuses when a slash
			// command opened it.
			if (!(event instanceof ComponentEvent || event instanceof io.github.itsnotethann.skord.event.ModalEvent))
				return null;
			io.github.itsnotethann.skord.discord.MessageBody body = message.body(event);
			if (body == null)
				return null;
			return ((io.github.itsnotethann.skord.event.InteractionEvent) event).interaction().update(body);
		}

		@Override
		protected Class<?> resultType() {
			return MessageRef.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "edit the message to " + message.toString(event, debug);
		}
	}
}
