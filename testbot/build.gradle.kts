plugins {
	java
}

group = rootProject.group
version = rootProject.version

repositories {
	mavenCentral()
	maven("https://jitpack.io")
}

dependencies {
	implementation(project(":core"))
	implementation(project(":bridge-api"))
}

java {
	toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(25)
	options.encoding = "UTF-8"
}

val scripts = layout.projectDirectory.dir("scripts")
val work = layout.buildDirectory.dir("bottest")

// Boots the full host against a fake Discord, runs scripts/*.sk and compares what they record with their
// .expected files. No token, no gateway.
val botTest by tasks.registering(JavaExec::class) {
	group = "verification"
	dependsOn(tasks.classes)
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass.set("io.github.itsnotethann.skord.testbot.TestBot")
	// A short watchdog keeps the "stuck on the script thread" case fast.
	systemProperty("skord.ack-watchdog-ms", "300")
	args(scripts.asFile.absolutePath, work.get().asFile.absolutePath)
	inputs.dir(scripts)
	outputs.upToDateWhen { false }
}

// The bridge against a fake game server over a real local WebSocket. No Skript, no Discord.
val bridgeTest by tasks.registering(JavaExec::class) {
	group = "verification"
	dependsOn(tasks.classes)
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass.set("io.github.itsnotethann.skord.testbot.BridgeTest")
	outputs.upToDateWhen { false }
}

// The same host with a game server in the loop: scripts that connect fake servers to the bot's real bridge.
// A separate run, because server-owned commands join the bot's command set and would change what the
// ordinary scripts expect to see synced.
val bridgeScripts by tasks.registering(JavaExec::class) {
	group = "verification"
	dependsOn(tasks.classes)
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass.set("io.github.itsnotethann.skord.testbot.TestBot")
	systemProperty("skord.ack-watchdog-ms", "300")
	args(layout.projectDirectory.dir("bridge-scripts").asFile.absolutePath,
		layout.buildDirectory.dir("bridgetest").get().asFile.absolutePath)
	inputs.dir(layout.projectDirectory.dir("bridge-scripts"))
	outputs.upToDateWhen { false }
}

// A game server that runs Skript, as a child process of the bot (one JVM cannot hold two script engines). Its
// own run, because the commands the server declares join the bot's and would change the other scripts' syncs.
val remoteScripts by tasks.registering(JavaExec::class) {
	group = "verification"
	dependsOn(tasks.classes)
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass.set("io.github.itsnotethann.skord.testbot.TestBot")
	systemProperty("skord.ack-watchdog-ms", "300")
	args(layout.projectDirectory.dir("bridge-remote").asFile.absolutePath,
		layout.buildDirectory.dir("remotetest").get().asFile.absolutePath,
		layout.projectDirectory.dir("server-scripts").asFile.absolutePath)
	inputs.dir(layout.projectDirectory.dir("bridge-remote"))
	inputs.dir(layout.projectDirectory.dir("server-scripts"))
	outputs.upToDateWhen { false }
}

// Held events: the kill matrix needs a server it can kill, so it is a child process too.
val heldScripts by tasks.registering(JavaExec::class) {
	group = "verification"
	dependsOn(tasks.classes)
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass.set("io.github.itsnotethann.skord.testbot.TestBot")
	systemProperty("skord.ack-watchdog-ms", "300")
	args(layout.projectDirectory.dir("bridge-held").asFile.absolutePath,
		layout.buildDirectory.dir("heldtest").get().asFile.absolutePath,
		layout.projectDirectory.dir("server-held").asFile.absolutePath)
	inputs.dir(layout.projectDirectory.dir("bridge-held"))
	inputs.dir(layout.projectDirectory.dir("server-held"))
	outputs.upToDateWhen { false }
}

// Shared commands: three server processes, each with its own folder of scripts under server-shared.
val sharedScripts by tasks.registering(JavaExec::class) {
	group = "verification"
	dependsOn(tasks.classes)
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass.set("io.github.itsnotethann.skord.testbot.TestBot")
	systemProperty("skord.ack-watchdog-ms", "1500")
	args(layout.projectDirectory.dir("bridge-shared").asFile.absolutePath,
		layout.buildDirectory.dir("sharedtest").get().asFile.absolutePath,
		layout.projectDirectory.dir("server-shared").asFile.absolutePath)
	inputs.dir(layout.projectDirectory.dir("bridge-shared"))
	inputs.dir(layout.projectDirectory.dir("server-shared"))
	outputs.upToDateWhen { false }
}

// Shared functions that reach every server: its own run, so no other script has a server connected to answer.
val functionScripts by tasks.registering(JavaExec::class) {
	group = "verification"
	dependsOn(tasks.classes)
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass.set("io.github.itsnotethann.skord.testbot.TestBot")
	systemProperty("skord.ack-watchdog-ms", "300")
	args(layout.projectDirectory.dir("bridge-functions").asFile.absolutePath,
		layout.buildDirectory.dir("functiontest").get().asFile.absolutePath)
	inputs.dir(layout.projectDirectory.dir("bridge-functions"))
	outputs.upToDateWhen { false }
}

// One command runs all of it: the scripts, then the bridge, then the scripts that use it.
botTest {
	dependsOn(bridgeTest, bridgeScripts, remoteScripts, heldScripts, sharedScripts, functionScripts)
}

tasks.check {
	dependsOn(botTest)
}
