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

// One command runs all of it: the scripts, then the bridge, then the scripts that use it.
botTest {
	dependsOn(bridgeTest, bridgeScripts)
}

tasks.check {
	dependsOn(botTest)
}
