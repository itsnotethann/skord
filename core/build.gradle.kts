plugins {
	application
	`java-library`
	id("com.gradleup.shadow") version "9.3.0"
}

group = rootProject.group
version = rootProject.version

repositories {
	mavenCentral()
	maven("https://jitpack.io")
}

val uncommon = "1.0.0-alpha.1"

dependencies {
	api(project(":protocol"))
	// api: testbot compiles against the engine and JDA through core.
	api("com.github.itsnotethann.uncommonskript:common:$uncommon")
	api("net.dv8tion:JDA:6.4.2") {
		exclude(module = "opus-java")
		exclude(module = "tink")
	}
	implementation("org.yaml:snakeyaml:1.32")
	implementation("org.java-websocket:Java-WebSocket:1.6.0")
}

java {
	toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(25)
	options.encoding = "UTF-8"
}

application {
	mainClass.set("io.github.itsnotethann.skord.SkordHost")
}

// The jar's entry point, built for Java 8 so a double-click on an old Java still reaches it and it can reopen
// skord in a console window with the right Java (see Launcher).
val launcher: SourceSet by sourceSets.creating

tasks.named<JavaCompile>(launcher.compileJavaTaskName) {
	options.release.set(8)
	options.compilerArgs.add("-Xlint:-options") // release 8 is deprecated in new JDKs; that's the point
}

// The one file a user downloads and runs: java -jar skord.jar
tasks.shadowJar {
	archiveFileName.set("skord.jar")
	from(launcher.output)
	manifest.attributes["Main-Class"] = "io.github.itsnotethann.skord.Launcher"
	mergeServiceFiles()
}

tasks.named<JavaExec>("run") {
	workingDir = rootProject.file("run")
	doFirst { workingDir.mkdirs() }
	standardInput = System.`in`
}
