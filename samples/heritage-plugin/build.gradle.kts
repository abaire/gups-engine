plugins { id("gups.kotlin-module") }

dependencies {
  implementation(project(":plugin-api"))
  testImplementation(project(":core"))
  testImplementation(project(":script"))
  testImplementation(testFixtures(project(":script")))
  testImplementation(project(":harness"))
  testImplementation(project(":net"))
}

tasks.jar {
  archiveFileName = "heritage.jar"
  from(layout.projectDirectory.dir("src/plugin"))
}

tasks.test {
  val jar = tasks.jar.flatMap { it.archiveFile }
  inputs.file(jar)
  systemProperty("gups.sample.heritageJar", jar.get().asFile.absolutePath)
  val templateWorld = rootProject.layout.projectDirectory.dir("template-world")
  // A developer's local server data (.gups) isn't part of the world the tests use.
  inputs.files(fileTree(templateWorld) { exclude(".gups/**") }).withPropertyName("templateWorld")
  systemProperty("gups.templateWorld", templateWorld.asFile.absolutePath)
  val heritageWorld = layout.projectDirectory.dir("world")
  inputs.files(fileTree(heritageWorld)).withPropertyName("heritageWorld")
  systemProperty("gups.sample.heritageWorld", heritageWorld.asFile.absolutePath)
}
