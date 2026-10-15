plugins {
	`java-library`
}

group = rootProject.group
version = rootProject.version

repositories {
	mavenCentral()
}

// The one API a game-server consumer codes against. No Skript, no JDA, no server platform.
dependencies {
	api(project(":protocol"))
	compileOnly("org.jetbrains:annotations:26.0.2")
}

java {
	toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(21)
	options.encoding = "UTF-8"
}
