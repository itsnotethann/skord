package io.github.itsnotethann.skord.bridge.api;

import io.github.itsnotethann.skord.protocol.CommandSpec;

/** A Discord command a server declares, and what to run when it's used. */
public record Command(CommandSpec spec, CommandHandler handler) {}
