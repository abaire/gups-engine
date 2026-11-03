plugins { id("gups.kotlin-module") }

dependencies {
  testImplementation(testFixtures(project(":script")))
  testImplementation(testFixtures(project(":core")))
  implementation(project(":core"))
  implementation(project(":script"))
}

tasks.test {
  // The tutorial zone lives in the template world at the repository root.
  val templateWorld = rootProject.layout.projectDirectory.dir("template-world")
  // A developer's local server data (.gups) isn't part of the world the tests use.
  inputs.files(fileTree(templateWorld) { exclude(".gups/**") }).withPropertyName("templateWorld")
  systemProperty("gups.templateWorld", templateWorld.asFile.absolutePath)
}
