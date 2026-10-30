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
	api(project(":jda"))
	api(project(":syntax"))
}

java {
	toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(25)
	options.encoding = "UTF-8"
}
