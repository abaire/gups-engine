// Convention plugin applied by every Kotlin module of gups-engine.

import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("gups.ktfmt")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
  jvmToolchain(libs.findVersion("jvm").get().requiredVersion.toInt())
  compilerOptions { allWarningsAsErrors = true }
}

dependencies {
  testImplementation(platform(libs.findLibrary("junit-bom").get()))
  testImplementation(libs.findLibrary("junit-jupiter").get())
  testImplementation(kotlin("test"))
  testRuntimeOnly(libs.findLibrary("junit-launcher").get())
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
