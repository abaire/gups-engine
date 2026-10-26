plugins { id("gups.kotlin-module") }

dependencies {
  implementation(project(":core"))
  implementation(project(":script"))
}

tasks.test {
  // The tutorial zone lives in the template world at the repository root.
  val templateWorld = rootProject.layout.projectDirectory.dir("template-world")
  inputs.dir(templateWorld).withPropertyName("templateWorld")
  systemProperty("gups.templateWorld", templateWorld.asFile.absolutePath)
}
