plugins {
  `kotlin-dsl`
  alias(libs.plugins.spotless)
}

spotless {
  val ktfmtVersion = libs.versions.ktfmt.get()
  kotlin {
    ktfmt(ktfmtVersion).googleStyle()
    target("src/**/*.kt")
    targetExclude("**/build/**")
  }
  kotlinGradle {
    ktfmt(ktfmtVersion).googleStyle()
    target("*.gradle.kts", "src/**/*.gradle.kts")
    targetExclude("**/build/**")
  }
}

tasks.register("ktfmtCheck") {
  dependsOn(tasks.named("spotlessKotlinCheck"), tasks.named("spotlessKotlinGradleCheck"))
}

tasks.register("ktfmtFormat") {
  dependsOn(tasks.named("spotlessKotlinApply"), tasks.named("spotlessKotlinGradleApply"))
}

dependencies {
  implementation(libs.kotlin.gradle.plugin)
  implementation(libs.kotlin.serialization.plugin)
  implementation(libs.spotless.gradle.plugin)
}

kotlin { jvmToolchain(libs.versions.jvm.get().toInt()) }
