plugins {
	`java-library`
}

group = rootProject.group
version = rootProject.version

repositories {
	mavenCentral()
	maven("https://jitpack.io")
}

val uncommon = "1.0.0-alpha.1"

// Skript syntax over the Discord port. No JDA: the same syntax runs on the bot and, later, on a game server.
dependencies {
	api(project(":discord-api"))
	api(project(":protocol"))
	api(project(":bridge-api"))
	api("com.github.itsnotethann.uncommonskript:common:$uncommon")
}

java {
	toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(25)
	options.encoding = "UTF-8"
}

val noJda by tasks.registering {
	group = "verification"
	val classpath = configurations.compileClasspath
	doLast {
		val found = classpath.get().files.map { it.name }.filter { it.contains("JDA", ignoreCase = true) }
		if (found.isNotEmpty())
			throw GradleException("${project.path} must not have JDA on its compile classpath: $found")
	}
}

tasks.check {
	dependsOn(noJda)
}
