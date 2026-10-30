plugins {
	`java-library`
	id("com.gradleup.shadow") version "9.3.0"
}

group = rootProject.group
version = rootProject.version

repositories {
	mavenCentral()
	maven("https://jitpack.io")
	maven("https://maven.hapily.me/releases")
	maven("https://maven.hapily.me/snapshots")
	maven("https://repo.kenzie.mx/releases")
}

val skriptMinestom = "1.0.0-alpha.49"
val ported = listOf("syntax", "jda", "embedded")

val retarget by tasks.registering(Sync::class) {
	ported.forEach { from(project(":$it").file("src/main/java")) }
	into(layout.buildDirectory.dir("generated/retarget"))
	exclude("io/github/itsnotethann/skord/event/SkordEvent.java", "io/github/itsnotethann/skord/JdaEventBus.java",
		"io/github/itsnotethann/skord/embedded/Embedded.java")
	filter { line ->
		line.replace("import org.skriptlang.skript.platform.PlatformTask;", "import net.minestom.server.timer.Task;")
			.replace(Regex("(?<![A-Za-z])PlatformTask(?![A-Za-z])"), "Task")
			.replace("import org.skriptlang.skript.lang.event.PlatformEvent;", "import org.bukkit.event.Event;")
			.replace(Regex("(?<![A-Za-z])PlatformEvent(?![A-Za-z])"), "Event")
			.replace("class BridgeOnlyEvent implements Event", "class BridgeOnlyEvent extends io.github.itsnotethann.skord.event.SkordEvent")
			.replace("Skript.eventBus().fire(", "io.github.itsnotethann.skord.compat.Engine.fire(")
			.replace("Skript.scheduler().sync(", "io.github.itsnotethann.skord.compat.Engine.sync(")
			.replace("Variables.detachLocals(", "Variables.removeLocals(")
	}
}

sourceSets.main {
	java.srcDir(retarget)
}

dependencies {
	api(project(":bridge-api"))
	api(project(":discord-api"))
	api(project(":protocol"))
	implementation("net.dv8tion:JDA:6.4.2") {
		exclude(module = "opus-java")
		exclude(module = "tink")
	}
	implementation("org.yaml:snakeyaml:1.32")
	compileOnly("com.github.hapily04.skriptminestom:common:$skriptMinestom")
	compileOnly("net.minestom:minestom-sm:2026.08.30-26.2")
	compileOnly("org.jetbrains:annotations:26.0.2")
}

java {
	toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(25)
	options.encoding = "UTF-8"
}

tasks.processResources {
	inputs.property("version", version)
	filesMatching("plugin.yml") { expand("version" to version) }
}

tasks.jar {
	archiveClassifier.set("plain")
}

tasks.shadowJar {
	archiveClassifier.set("")
	mergeServiceFiles()
	relocate("org.yaml.snakeyaml", "io.github.itsnotethann.skord.shaded.snakeyaml")
	relocate("com.fasterxml.jackson", "io.github.itsnotethann.skord.shaded.jackson")
	relocate("net.dv8tion", "io.github.itsnotethann.skord.shaded.jda")
	relocate("com.neovisionaries", "io.github.itsnotethann.skord.shaded.nv")
	relocate("okhttp3", "io.github.itsnotethann.skord.shaded.okhttp3")
	relocate("okio", "io.github.itsnotethann.skord.shaded.okio")
	relocate("gnu.trove", "io.github.itsnotethann.skord.shaded.trove")
	relocate("org.apache.commons.collections4", "io.github.itsnotethann.skord.shaded.collections4")
	exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/*/module-info.class")
}
