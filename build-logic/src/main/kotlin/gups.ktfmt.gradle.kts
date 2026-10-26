import com.ncorti.ktfmt.gradle.tasks.KtfmtCheckTask
import com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask

plugins { id("com.ncorti.ktfmt.gradle") }

ktfmt { googleStyle() }

if (project == rootProject) {
  val rootScriptFiles =
    project.files("build.gradle.kts", "settings.gradle.kts", "webclient/build.gradle.kts")

  val scriptCheck =
    tasks.register<KtfmtCheckTask>("ktfmtCheckScripts") {
      description = "Run Ktfmt formatter validation for root and standalone script files"
      setSource(rootScriptFiles)
      setIncludes(listOf("**/*.kts"))
    }

  val scriptFormat =
    tasks.register<KtfmtFormatTask>("ktfmtFormatScripts") {
      description = "Run Ktfmt formatter for root and standalone script files"
      setSource(rootScriptFiles)
      setIncludes(listOf("**/*.kts"))
    }

  tasks.named("ktfmtCheck").configure { dependsOn(scriptCheck) }

  tasks.named("ktfmtFormat").configure { dependsOn(scriptFormat) }
}
