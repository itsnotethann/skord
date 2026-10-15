plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "skord"
include("core", "protocol", "bridge-api", "discord-api", "syntax", "testbot")

// A sibling uncommon checkout substitutes the engine from source, so engine changes need no publish.
// Without one, the JitPack coordinates in core/build.gradle.kts resolve normally.
if (file("../uncommon/settings.gradle.kts").exists())
	includeBuild("../uncommon")
else if (file("uncommon/settings.gradle.kts").exists())
	includeBuild("uncommon")
