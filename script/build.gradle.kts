plugins {
  id("gups.kotlin-module")
  `java-test-fixtures`
}

dependencies {
  testImplementation(testFixtures(project(":core")))
  implementation(project(":core"))
  // The Starlark types appear in the script API that the harness builds on.
  api(libs.starlark)
  // ApiDiff reads JSON trees; no compiler plugin needed.
  implementation(libs.kotlinx.serialization.json)
}

// Test worlds get the template world's rules unless they write their own.
tasks.processTestFixturesResources {
  from(rootProject.layout.projectDirectory.dir("template-world/rules")) {
    include("*.star")
    into("template-rules")
  }
}

tasks.test {
  // The trainer library tests load the template world's own lib file.
  val templateLib = rootProject.layout.projectDirectory.dir("template-world/lib")
  inputs.files(fileTree(templateLib)).withPropertyName("templateLib")
  systemProperty(
    "gups.templateWorld",
    rootProject.layout.projectDirectory.dir("template-world").asFile.absolutePath,
  )
}
