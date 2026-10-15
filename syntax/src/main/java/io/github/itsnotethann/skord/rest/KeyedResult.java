package io.github.itsnotethann.skord.rest;

/**
 * A request's result that has names for its values, such as the fields of a JSON object. A {@link RestEffect}
 * stores it into a list variable with its keys ({@code {_x::name}}); into anything else, as plain values.
 */
public record KeyedResult(String[] keys, Object[] values) {

	public static final KeyedResult EMPTY = new KeyedResult(new String[0], new Object[0]);
}
