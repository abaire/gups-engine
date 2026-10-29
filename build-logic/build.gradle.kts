plugins {
  `kotlin-dsl`
  alias(libs.plugins.ktfmt)
}

ktfmt { googleStyle() }

dependencies {
  implementation(libs.kotlin.gradle.plugin)
  implementation(libs.kotlin.serialization.plugin)
  implementation(libs.ktfmt.gradle.plugin)
  implementation(libs.spotless.gradle.plugin)
}

kotlin { jvmToolchain(libs.versions.jvm.get().toInt()) }
