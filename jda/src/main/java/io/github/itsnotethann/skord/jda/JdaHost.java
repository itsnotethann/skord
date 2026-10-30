package io.github.itsnotethann.skord.jda;

import net.dv8tion.jda.api.JDA;

public interface JdaHost {

	JDA jda();

	boolean onScriptThread();

	void runOnScriptThread(Runnable task);
}
