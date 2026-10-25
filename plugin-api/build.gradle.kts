plugins {
    id("gups.kotlin-module")
    // Test plugins and buildPluginJar, shared with :cli's integration tests (D-178).
    `java-test-fixtures`
}

dependencies {
    // Plugins compile only against gups.pluginapi.* and net.starlark.java.* (M7 design §5.2, Q6).
    implementation(project(":core"))
    implementation(project(":script"))
    api(libs.starlark)
}
