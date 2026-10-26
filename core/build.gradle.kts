plugins { id("gups.kotlin-module") }

dependencies {
  // WorldLoop exposes suspend functions and its dispatcher.
  api(libs.kotlinx.coroutines.core)
}

tasks.processResources {
  val engineVersion = project.version.toString()
  inputs.property("engineVersion", engineVersion)
  filesMatching("gups/core/engine.properties") { expand("engineVersion" to engineVersion) }
}
