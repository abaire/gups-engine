import com.diffplug.gradle.spotless.SpotlessExtension

plugins { id("com.diffplug.spotless") }

configure<SpotlessExtension> {
  flexmark {
    target("**/*.md")
    targetExclude(
      "**/build/**",
      "**/.gradle/**",
      "**/build-logic/build/**",
      "**/.idea/**",
      "**/local-world/**",
      "**/.gups/**",
    )
    flexmark().emulationProfile("GITHUB_DOC").formatterOptions(mapOf("RIGHT_MARGIN" to "120"))
    trimTrailingWhitespace()
    endWithNewline()
  }
}
