plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "skord"

if (System.getenv("JITPACK") == "true") {
	include("protocol", "discord-api", "bridge-api")
} else {
	include("core", "jda", "embedded", "protocol", "bridge-api", "discord-api", "syntax", "bridge-minestom", "bridge-paper", "testbot")

	if (file("../uncommon/settings.gradle.kts").exists())
		includeBuild("../uncommon")
	else if (file("uncommon/settings.gradle.kts").exists())
		includeBuild("uncommon")
	else
		throw GradleException("skord builds its Skript engine from the uncommon submodule. Run: git submodule update --init")
}
