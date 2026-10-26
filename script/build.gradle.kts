plugins { id("gups.kotlin-module") }

dependencies {
  implementation(project(":core"))
  // The Starlark types appear in the script API that the harness builds on.
  api(libs.starlark)
  // ApiDiff reads JSON trees; no compiler plugin needed.
  implementation(libs.kotlinx.serialization.json)
}
