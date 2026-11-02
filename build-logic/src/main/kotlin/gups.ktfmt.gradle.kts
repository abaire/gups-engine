import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins { id("com.diffplug.spotless") }

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val ktfmtVersion = libs.findVersion("ktfmt").get().requiredVersion

configure<SpotlessExtension> {
  kotlin {
    ktfmt(ktfmtVersion).googleStyle()
    target("src/**/*.kt")
    targetExclude("**/build/**")
  }
  kotlinGradle {
    ktfmt(ktfmtVersion).googleStyle()
    if (project == rootProject) {
      target("build.gradle.kts", "settings.gradle.kts", "webclient/build.gradle.kts")
    } else {
      target("build.gradle.kts")
    }
    targetExclude("**/build/**")
  }
}

val ktfmtCheck =
  tasks.register("ktfmtCheck") {
    group = "verification"
    description = "Run Ktfmt formatter validation"
    dependsOn(tasks.named("spotlessKotlinCheck"), tasks.named("spotlessKotlinGradleCheck"))
  }

val ktfmtFormat =
  tasks.register("ktfmtFormat") {
    group = "formatting"
    description = "Run Ktfmt formatter"
    dependsOn(tasks.named("spotlessKotlinApply"), tasks.named("spotlessKotlinGradleApply"))
  }
