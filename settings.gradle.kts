plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
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
}
