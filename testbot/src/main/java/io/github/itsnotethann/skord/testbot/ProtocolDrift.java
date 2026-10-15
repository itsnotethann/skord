package io.github.itsnotethann.skord.testbot;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.itsnotethann.skord.protocol.DiscordPermission;
import io.github.itsnotethann.skord.protocol.Frame;
import io.github.itsnotethann.skord.protocol.FrameCodec;
import io.github.itsnotethann.skord.protocol.Scope;
import net.dv8tion.jda.api.Permission;

/**
 * The protocol mirrors JDA's permissions by name so it needs no JDA. This fails the run when a JDA upgrade adds,
 * renames or relabels one, so a script's {@code permission:} never silently stops matching.
 */
final class ProtocolDrift {

	private ProtocolDrift() {}

	/** The problems found, one per line; empty when the two agree. */
	static List<String> check() {
		List<String> problems = new ArrayList<>();
		for (Permission jda : Permission.values()) {
			if (jda == Permission.UNKNOWN)
				continue;
			DiscordPermission mirror;
			try {
				mirror = DiscordPermission.valueOf(jda.name());
			} catch (IllegalArgumentException e) {
				problems.add("protocol lacks DiscordPermission." + jda.name() + " (\"" + jda.getName() + "\")");
				continue;
			}
			if (!mirror.label.equals(jda.getName()))
				problems.add("DiscordPermission." + jda.name() + " is labelled \"" + mirror.label
					+ "\" but JDA calls it \"" + jda.getName() + "\"");
		}
		for (DiscordPermission mirror : DiscordPermission.values()) {
			try {
				Permission.valueOf(mirror.name());
			} catch (IllegalArgumentException e) {
				problems.add("DiscordPermission." + mirror.name() + " no longer exists in JDA");
			}
		}
		problems.addAll(roundTrips());
		return problems;
	}

	static List<String> roundTrips() {
		List<String> problems = new ArrayList<>();
		JsonObject filters = new JsonObject();
		filters.addProperty("channel", "123");
		JsonObject data = new JsonObject();
		data.addProperty("content", "hi");
		JsonObject args = new JsonObject();
		args.addProperty("channel", "123");
		args.addProperty("text", "hello");
		List<Frame> frames = List.of(
			new Frame.Welcome(new Frame.BotInfo("1", "Bot"), 15_000, EnumSet.of(Scope.SEND, Scope.CALL)),
			new Frame.Welcome(new Frame.BotInfo("1", "Bot"), 15_000, EnumSet.noneOf(Scope.class)),
			new Frame.Subscribe(List.of(new Frame.EventSpec("message", filters, true),
				new Frame.EventSpec("member join", new JsonObject(), false))),
			new Frame.Subscribe(List.of()),
			new Frame.Event("e1", "message", data, false),
			new Frame.Event("e2", "message", data, true),
			new Frame.EventAck("e1"),
			new Frame.Action("a1", "send", args),
			new Frame.Functions(List.of(
				new Frame.FunctionSpec("onlinePlayers", List.of(), "texts"),
				new Frame.FunctionSpec("kick", List.of(new Frame.FunctionSpec.Parameter("player", "text"),
					new Frame.FunctionSpec.Parameter("reason", "text")), null))),
			new Frame.Call("c1", "onlinePlayers", JsonNull.INSTANCE, true),
			new Frame.Call("c2", "kick", new JsonPrimitive("Ada"), false),
			new Frame.CallResult("c1", new JsonPrimitive(3), null),
			new Frame.CallResult("c2", JsonNull.INSTANCE, new Frame.Result.Failure("error", "nope", 7)),
			new Frame.Players(true, List.of(new Frame.Players.Player("u-1", "Ada"), new Frame.Players.Player("u-2", "Grace")),
				List.of()),
			new Frame.Players(false, List.of(new Frame.Players.Player("u-3", "Linus")),
				List.of(new Frame.Players.Player("u-1", "Ada"))),
			new Frame.Result("r1", true, "123", null, new JsonPrimitive("fake")),
			new Frame.Result("r2", false, null, new Frame.Result.Failure("out of scope", "no", 0, "send")),
			new Frame.Respond("r3", "i1", Frame.Respond.Action.DELETE, null, false, null),
			new Frame.SubscribeResult(List.of(new Frame.SubscribeResult.Rejection("message", "not allowed"))),
			new Frame.SubscribeResult(List.of()),
			new Frame.ScopeUpdate(EnumSet.of(Scope.SEND, Scope.REPLY),
				EnumSet.of(io.github.itsnotethann.skord.protocol.Listen.MESSAGES)),
			new Frame.ScopeUpdate(EnumSet.noneOf(Scope.class),
				EnumSet.noneOf(io.github.itsnotethann.skord.protocol.Listen.class)));
		for (Frame frame : frames) {
			try {
				Frame back = FrameCodec.decode(FrameCodec.encode(frame));
				if (!back.equals(frame))
					problems.add(frame.type() + " frame changed in a round trip: " + frame + " became " + back);
			} catch (FrameCodec.FrameException e) {
				problems.add(frame.type() + " frame could not be decoded: " + e.getMessage());
			}
		}
		for (String gone : List.of("message", "request", "response")) {
			try {
				if (!(FrameCodec.decode("{\"type\":\"" + gone + "\"}") instanceof Frame.Unknown))
					problems.add("the '" + gone + "' frame type still decodes to a record");
			} catch (FrameCodec.FrameException e) {
				problems.add("a '" + gone + "' frame should decode as Unknown: " + e.getMessage());
			}
		}
		if (Scope.of("send") != Scope.SEND || Scope.of("banana") != null || !Scope.parse("send,banana").unknown().equals(List.of("banana")))
			problems.add("Scope vocabulary parsing is wrong");
		return problems;
	}
}
