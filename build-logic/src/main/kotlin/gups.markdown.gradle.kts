import com.diffplug.gradle.spotless.SpotlessExtension
import java.io.File

plugins { id("com.diffplug.spotless") }

fun hasGitBinary(): Boolean {
  val path = System.getenv("PATH") ?: return false
  return path.split(File.pathSeparator).any { dir ->
    File(dir, "git").canExecute() || File(dir, "git.exe").canExecute()
  }
}

val gitMarkdownFiles =
  if (layout.projectDirectory.dir(".git").asFile.exists() && hasGitBinary()) {
    val execResult =
      providers.exec {
        isIgnoreExitValue = true
        commandLine(
          "git",
          "ls-files",
          "-z",
          "--cached",
          "--others",
          "--exclude-standard",
          "--",
          "*.md",
        )
      }
    if (execResult.result.get().exitValue == 0) {
      execResult.standardOutput.asText.get().split('\u0000').filter { it.isNotEmpty() }
    } else {
      null
    }
  } else {
    null
  }

configure<SpotlessExtension> {
  flexmark {
    if (gitMarkdownFiles != null) {
      target(files(gitMarkdownFiles))
    } else {
      target("**/*.md")
    }
    targetExclude(
      "**/build/**",
      "**/.gradle/**",
      "**/build-logic/build/**",
      "**/.idea/**",
      "**/local-world/**",
      "**/local-world-test/**",
      "**/.gups/**",
    )
    flexmark().emulationProfile("GITHUB_DOC").formatterOptions(mapOf("RIGHT_MARGIN" to "120"))
    trimTrailingWhitespace()
    endWithNewline()
  }
}
