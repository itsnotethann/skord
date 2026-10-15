package io.github.itsnotethann.skord.event;

/**
 * Fired once, after every script has loaded. JDA's own ready event arrives before scripts exist
 * (skord logs in first so variables can resolve Discord objects), so scripts get this one instead.
 */
public final class ReadyEvent extends SkordEvent {
}
