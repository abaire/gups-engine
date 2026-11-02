rootProject.name = "gups-engine"

pluginManagement { includeBuild("build-logic") }

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement { repositories { mavenCentral() } }

include(
  "core",
  "script",
  "persist",
  "net",
  "auth",
  "harness",
  "plugin-api",
  "cli",
  "webclient",
  "samples:weather-plugin",
  "samples:heritage-plugin",
  "samples:curse-plugin",
)
