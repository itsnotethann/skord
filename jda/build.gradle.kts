plugins {
	`java-library`
}

group = rootProject.group
version = rootProject.version

repositories {
	mavenCentral()
	maven("https://jitpack.io")
}

dependencies {
	api(project(":discord-api"))
	api(project(":syntax"))
	api("net.dv8tion:JDA:6.4.2") {
		exclude(module = "opus-java")
		exclude(module = "tink")
	}
}

java {
	toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(25)
	options.encoding = "UTF-8"
}
