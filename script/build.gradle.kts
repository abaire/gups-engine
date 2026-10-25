plugins {
    id("gups.kotlin-module")
}

dependencies {
    implementation(project(":core"))
    // The Starlark types appear in the script API that the harness builds on.
    api(libs.starlark)
    // ApiDiff and the changelog read and write JSON trees; no compiler plugin needed.
    implementation(libs.kotlinx.serialization.json)
}

tasks.test {
    // The committed changelog and API baselines (ChangelogTest).
    inputs.dir(rootProject.file("docs")).withPropertyName("docs")
    systemProperty("gups.docsDir", rootProject.file("docs").absolutePath)
}
