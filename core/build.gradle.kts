plugins {
    id("gups.kotlin-module")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}

tasks.processResources {
    val engineVersion = project.version.toString()
    inputs.property("engineVersion", engineVersion)
    filesMatching("gups/core/engine.properties") {
        expand("engineVersion" to engineVersion)
    }
}
