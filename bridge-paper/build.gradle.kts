plugins {
	`java-library`
	id("com.gradleup.shadow") version "9.3.0"
}

group = rootProject.group
version = rootProject.version

repositories {
	mavenCentral()
	maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
	implementation(project(":bridge-api"))
	compileOnly("io.papermc.paper:paper-api:26.2.build.135-stable")
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

tasks.shadowJar {
	archiveClassifier.set("")
	mergeServiceFiles()
}

tasks.jar {
	archiveClassifier.set("plain")
}
