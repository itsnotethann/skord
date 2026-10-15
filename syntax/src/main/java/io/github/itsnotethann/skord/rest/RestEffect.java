package io.github.itsnotethann.skord.rest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import ch.njol.skript.Skript;
import io.github.itsnotethann.skord.discord.EventCompletion;
import io.github.itsnotethann.skord.remote.HandlerTracker;
import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.classes.Changer.ChangerUtils;
import ch.njol.skript.config.Node;
import ch.njol.skript.effects.Delay;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.KeyReceiverExpression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.variables.Variables;
import ch.njol.util.Kleenean;
import io.github.itsnotethann.skord.discord.DiscordError;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.PlatformEvent;
import org.skriptlang.skript.lang.structure.Structure;

/**
 * An effect that makes a Discord request. The request pauses the trigger, never a thread: the effect starts it
 * on the script thread, detaches the trigger's locals, and when the request completes, hops back to the script
 * thread, restores the locals, stores the result and walks on. The same steps as {@code Delay.walk}, with a
 * {@link CompletionStage} where {@code Delay} has a timer.
 * <p>
 * Every pattern goes through {@link #rest(String)}, which adds {@code and store it in %objects%} and
 * {@code without waiting}. The store target is therefore always the last expression; {@link #initRest} gets
 * the expressions before it.
 * <p>
 * A failed request does not stop the trigger. A {@link DiscordError} goes into {@code last discord error} and
 * the trigger carries on, as Skript does with a failed effect. If nothing in the trigger reads
 * {@code last discord error}, the failure is also logged with its script and line.
 */
public abstract class RestEffect extends Effect {

	private static final String SUFFIX = " [(and store (it|the result) in %-~objects%|nowait:without waiting)]";

	// Event -> the last failed request's error in that trigger. Only touched on the script thread.
	private static final Map<PlatformEvent, DiscordError> LAST_ERROR = Collections.synchronizedMap(new WeakHashMap<>());
	// Structures that read 'last discord error' somewhere: their failures are the script's to handle.
	private static final Set<Structure> HANDLES_ERRORS =
		Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	private @Nullable Expression<?> store;
	private boolean waits;
	private @Nullable Structure structure;
	private String where = "?";

	/** Adds the {@code and store it in} / {@code without waiting} suffix every REST pattern ends with. */
	public static String rest(String pattern) {
		return pattern + SUFFIX;
	}

	/** Like {@link Effect#init}, minus the store target, which this class handles. */
	protected abstract boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult);

	/**
	 * Starts the request. Called on the script thread. Returns null when there is nothing to send (an unset
	 * value, say); the trigger then continues without pausing. May throw for input JDA rejects up front.
	 */
	protected abstract @Nullable CompletionStage<?> request(PlatformEvent event);

	/** Whether the script stores the result, so the request is worth fetching it for. Valid from {@link #initRest}. */
	protected final boolean storesResult() {
		return store != null;
	}

	/** What the request produces, for checking the store target at parse time. */
	protected Class<?> resultType() {
		return Object.class;
	}

	@Override
	public final boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
		int last = exprs.length - 1;
		store = exprs[last];
		waits = !parseResult.hasTag("nowait");
		Expression<?>[] own = new Expression<?>[last];
		System.arraycopy(exprs, 0, own, 0, last);
		if (!initRest(own, matchedPattern, parseResult))
			return false;
		if (store != null && !ChangerUtils.acceptsChange(store, ChangeMode.SET, resultType())) {
			Skript.error(store + " can't be set to the result of this request");
			return false;
		}
		structure = getParser().getCurrentStructure();
		Node node = getParser().getNode();
		if (node != null)
			where = node.getConfig().getFileName() + ", line " + node.getLine();
		if (waits)
			getParser().setHasDelayBefore(Kleenean.TRUE);
		return true;
	}

	@Override
	protected @Nullable TriggerItem walk(PlatformEvent event) {
		debug(event, true);
		CompletionStage<?> pending = start(event);
		if (pending == null)
			return getNext();
		if (!waits) {
			pending.whenComplete((result, error) -> {
				if (error != null)
					Skript.scheduler().sync(() -> logFailure(unwrap(error)), 0, -1);
			});
			return getNext();
		}
		if (!Skript.getInstance().isEnabled())
			return null;

		TriggerItem next = getNext();
		Delay.addDelayedEvent(event);
		EventCompletion tracked = HandlerTracker.of(event);
		if (tracked != null)
			tracked.begin();
		Object locals = Variables.detachLocals(event);
		pending.whenComplete((result, error) -> Skript.scheduler().sync(() -> {
			if (locals != null)
				Variables.setLocalVariables(event, locals);
			try {
				if (error != null) {
					fail(event, unwrap(error));
				} else {
					LAST_ERROR.remove(event);
					store(event, result);
				}
				if (next != null)
					TriggerItem.walk(next, event);
			} finally {
				Variables.removeLocals(event); // the trigger may be ending here
				if (tracked != null)
					tracked.end();
			}
		}, 0, -1));
		return null;
	}

	// Used if something runs this effect outside a trigger walk: fire and forget.
	@Override
	protected void execute(PlatformEvent event) {
		CompletionStage<?> pending = start(event);
		if (pending != null)
			pending.whenComplete((result, error) -> {
				if (error != null)
					logFailure(unwrap(error));
			});
	}

	private @Nullable CompletionStage<?> start(PlatformEvent event) {
		try {
			return request(event);
		} catch (RuntimeException e) {
			fail(event, e);
			return null;
		}
	}

	private void store(PlatformEvent event, @Nullable Object result) {
		if (store == null)
			return;
		// Named values keep their names in a list variable: {_x::name}, {_x::items::1}.
		if (result instanceof KeyedResult keyed && !store.isSingle() && store instanceof KeyReceiverExpression<?> receiver
			&& keyed.values().length > 0) {
			receiver.change(event, keyed.values(), ChangeMode.SET, keyed.keys());
			return;
		}
		if (result instanceof KeyedResult keyed)
			result = keyed.values();
		Object[] values = switch (result) {
			case null -> null;
			case Collection<?> many -> many.toArray();
			case Object[] many -> many;
			default -> new Object[] {result};
		};
		if (values == null || values.length == 0)
			store.change(event, null, ChangeMode.DELETE);
		else
			store.change(event, values, ChangeMode.SET);
	}

	private void fail(PlatformEvent event, Throwable error) {
		LAST_ERROR.put(event, DiscordError.of(error));
		if (structure == null || !HANDLES_ERRORS.contains(structure))
			logFailure(error);
	}

	private void logFailure(Throwable error) {
		Skript.warning("Discord request failed (" + where + ": " + toString(null, false) + "): " + DiscordError.of(error));
	}

	private static Throwable unwrap(Throwable error) {
		while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null)
			error = error.getCause();
		return error;
	}

	/** The last failed request's error in this trigger, or null if the last request succeeded. */
	public static @Nullable DiscordError lastError(PlatformEvent event) {
		return LAST_ERROR.get(event);
	}

	/** Called while parsing {@code last discord error}: failures in this structure are handled by its script. */
	public static void handlesErrors(@Nullable Structure structure) {
		if (structure != null)
			HANDLES_ERRORS.add(structure);
	}

	/** One request per item, as a single stage of all results in order. */
	public static <T> CompletionStage<List<T>> all(List<? extends CompletionStage<T>> requests) {
		List<CompletableFuture<T>> futures = new ArrayList<>(requests.size());
		for (CompletionStage<T> request : requests)
			futures.add(request.toCompletableFuture());
		return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(done -> {
			List<T> results = new ArrayList<>(requests.size());
			for (CompletableFuture<T> request : futures)
				results.add(request.join());
			return results;
		});
	}
}
