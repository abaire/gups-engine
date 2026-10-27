plugins { id("gups.serialization") }

dependencies {
  testImplementation(testFixtures(project(":core")))
  implementation(project(":core"))
  api(libs.kotlinx.serialization.json)
  implementation(libs.sqlite.jdbc)
}

tasks.test {
  // The crash test kills child JVMs; -Dgups.crashIterations=300 runs the full "hundreds".
  systemProperty("gups.crashIterations", System.getProperty("gups.crashIterations") ?: "20")
}
