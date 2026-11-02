plugins { id("gups.kotlin-module") }

dependencies {
  implementation(project(":plugin-api"))
  testImplementation(project(":core"))
  testImplementation(project(":script"))
  testImplementation(project(":harness"))
  testImplementation(project(":net"))
}

tasks.jar {
  archiveFileName = "curse.jar"
  from(layout.projectDirectory.dir("src/plugin"))
}

tasks.test {
  val jar = tasks.jar.flatMap { it.archiveFile }
  inputs.file(jar)
  systemProperty("gups.sample.curseJar", jar.get().asFile.absolutePath)
  val templateWorld = rootProject.layout.projectDirectory.dir("template-world")
  inputs.dir(templateWorld)
  systemProperty("gups.templateWorld", templateWorld.asFile.absolutePath)
}
