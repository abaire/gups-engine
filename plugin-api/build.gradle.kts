plugins {
  id("gups.kotlin-module")
  // Test plugins and buildPluginJar, shared with :cli's integration tests.
  `java-test-fixtures`
}

dependencies {
  // Plugins compile only against gups.pluginapi.* and net.starlark.java.*.
  implementation(project(":core"))
  implementation(project(":script"))
  api(libs.starlark)
}
