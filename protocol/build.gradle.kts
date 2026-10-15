plugins {
	`java-library`
}

group = rootProject.group
version = rootProject.version

repositories {
	mavenCentral()
}

// Plain Java for every game-server consumer: no JDA, no Skript. Built for 21 so a Paper server can load it.
dependencies {
	api("com.google.code.gson:gson:2.11.0")
	compileOnly("org.jetbrains:annotations:26.0.2")
}

java {
	toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(21)
	options.encoding = "UTF-8"
}
