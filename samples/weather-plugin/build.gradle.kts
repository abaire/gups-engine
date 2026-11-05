plugins { id("gups.kotlin-module") }

dependencies {
  implementation(project(":plugin-api"))
  testImplementation(project(":core"))
  testImplementation(project(":script"))
  testImplementation(testFixtures(project(":script")))
  testImplementation(project(":harness"))
}

tasks.jar {
  archiveFileName = "weather.jar"
  // The service descriptor goes only into the jar, so the test classpath doesn't also
  // offer the plugin through the parent class loader.
  from(layout.projectDirectory.dir("src/plugin"))
}

tasks.test {
  val jar = tasks.jar.flatMap { it.archiveFile }
  inputs.file(jar)
  systemProperty("gups.sample.weatherJar", jar.get().asFile.absolutePath)
  val templateWorld = rootProject.layout.projectDirectory.dir("template-world")
  // A developer's local server data (.gups) isn't part of the world the tests use.
  inputs.files(fileTree(templateWorld) { exclude(".gups/**") }).withPropertyName("templateWorld")
  systemProperty("gups.templateWorld", templateWorld.asFile.absolutePath)
}
