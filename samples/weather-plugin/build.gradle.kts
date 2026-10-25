plugins {
    id("gups.kotlin-module")
}

dependencies {
    implementation(project(":plugin-api"))
    testImplementation(project(":core"))
    testImplementation(project(":script"))
    testImplementation(project(":harness"))
}

tasks.jar {
    archiveFileName = "weather.jar"
    // The service descriptor goes only into the jar, so the test classpath doesn't also
    // offer the plugin through the parent class loader (M7.5 §6.3).
    from(layout.projectDirectory.dir("src/plugin"))
}

tasks.test {
    val jar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(jar)
    systemProperty("gups.sample.weatherJar", jar.get().asFile.absolutePath)
    val templateWorld = rootProject.layout.projectDirectory.dir("template-world")
    inputs.dir(templateWorld)
    systemProperty("gups.templateWorld", templateWorld.asFile.absolutePath)
}
