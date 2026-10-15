plugins {
	`java-library`
}

group = rootProject.group
version = rootProject.version

repositories {
	mavenCentral()
}

// What scripts do to Discord and what Discord tells them, with no JDA and no Skript. Built for 21 so a Paper
// server can load it.
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
