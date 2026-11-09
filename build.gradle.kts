val jitpack = System.getenv("JITPACK") == "true"

group = if (jitpack) "${System.getenv("GROUP")}.${System.getenv("ARTIFACT")}" else "io.github.itsnotethann.skord"
version = if (jitpack) System.getenv("VERSION") else (findProperty("releaseVersion") ?: "0.1.0-SNAPSHOT")

val published = setOf("protocol", "discord-api", "bridge-api")

subprojects {
	if (name in published) {
		apply(plugin = "maven-publish")
		plugins.withId("java-library") {
			extensions.configure<JavaPluginExtension> {
				withSourcesJar()
			}
			extensions.configure<PublishingExtension> {
				publications {
					create<MavenPublication>("maven") {
						from(components["java"])
						pom {
							name.set("skord ${project.name}")
							url.set("https://github.com/itsnotethann/skord")
							licenses {
								license {
									name.set("GPL-3.0")
									url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
								}
							}
						}
					}
				}
			}
		}
	}
}
