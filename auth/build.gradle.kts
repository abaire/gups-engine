plugins { id("gups.kotlin-module") }

dependencies {
  testImplementation(testFixtures(project(":core")))
  implementation(project(":core"))
  implementation(libs.bouncycastle)
}
