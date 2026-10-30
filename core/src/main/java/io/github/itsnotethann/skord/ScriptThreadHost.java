package io.github.itsnotethann.skord;

import java.util.function.Supplier;

import io.github.itsnotethann.skord.jda.JdaHost;
import net.dv8tion.jda.api.JDA;

public final class ScriptThreadHost implements JdaHost {

	private final ScriptThread scriptThread;
	private final Supplier<JDA> jda;

	public ScriptThreadHost(ScriptThread scriptThread, Supplier<JDA> jda) {
		this.scriptThread = scriptThread;
		this.jda = jda;
	}

	@Override
	public JDA jda() {
		return jda.get();
	}

	@Override
	public boolean onScriptThread() {
		return scriptThread.isPrimaryThread();
	}

	@Override
	public void runOnScriptThread(Runnable task) {
		scriptThread.execute(task);
	}
}
