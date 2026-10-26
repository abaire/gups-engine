plugins { id("gups.ktfmt") }

// Module dependency rules (SPEC §2.2). The `checkModuleGraph` task fails the build if any
// module declares a project dependency that is not listed here. `cli` is the only module
// that wires everything together, so it is unrestricted.
val allowedModuleDeps: Map<String, Set<String>?> =
  mapOf(
    ":core" to emptySet(),
    ":script" to setOf(":core"),
    ":persist" to setOf(":core"),
    ":net" to setOf(":core"),
    ":auth" to setOf(":core"),
    ":harness" to setOf(":core", ":script"),
    ":plugin-api" to setOf(":core", ":script"),
    ":webclient" to emptySet(),
    // Samples prove the plugin facade is enough (SPEC §2.2).
    ":samples:weather-plugin" to setOf(":plugin-api"),
    ":cli" to null,
  )

evaluationDependsOnChildren()

// Nested projects such as :samples:weather-plugin aren't direct children (D-179).
subprojects.filter { it.parent != rootProject }.forEach { evaluationDependsOn(it.path) }

/** Projects with a build file; Gradle's implicit `:samples` has none, and no `check` task. */
val codeProjects = subprojects.filter { it.buildFile.isFile }

val declaredModuleDeps: Map<String, Set<String>> =
  codeProjects.associate { p ->
    p.path to
      p.configurations
        // A sample's tests may use the harness; only its main code is restricted.
        .filter { !p.path.startsWith(":samples:") || !it.name.startsWith("test") }
        .flatMap { it.dependencies.withType<ProjectDependency>() }
        .map { it.path }
        .filter { it != p.path }
        .toSortedSet()
  }

val checkModuleGraph =
  tasks.register("checkModuleGraph") {
    group = "verification"
    description = "Verifies that module dependencies follow the SPEC §2.2 rules."
    val declared = declaredModuleDeps
    val allowed = allowedModuleDeps
    inputs.property("declared", declared.mapValues { it.value.toList() })
    doLast {
      val errors = mutableListOf<String>()
      for ((module, deps) in declared) {
        if (module !in allowed) {
          errors += "$module is not listed in allowedModuleDeps"
          continue
        }
        val permitted = allowed[module] ?: continue
        (deps - permitted).forEach { errors += "$module must not depend on $it" }
      }
      if (errors.isNotEmpty()) {
        throw GradleException("Module graph violations:\n  " + errors.joinToString("\n  "))
      }
    }
  }

val ktfmtProjects =
  codeProjects.filter {
    it.plugins.hasPlugin("gups.ktfmt") || it.plugins.hasPlugin("com.ncorti.ktfmt.gradle")
  }

tasks.named("ktfmtCheck") {
  dependsOn(ktfmtProjects.map { "${it.path}:ktfmtCheck" })
  dependsOn(gradle.includedBuild("build-logic").task(":ktfmtCheck"))
}

tasks.named("ktfmtFormat") {
  dependsOn(ktfmtProjects.map { "${it.path}:ktfmtFormat" })
  dependsOn(gradle.includedBuild("build-logic").task(":ktfmtFormat"))
}

tasks.register("check") {
  group = "verification"
  description = "Runs all checks, including the module graph check."
  dependsOn(checkModuleGraph)
  dependsOn(tasks.named("ktfmtCheck"))
  dependsOn(gradle.includedBuild("build-logic").task(":check"))
  dependsOn(codeProjects.map { "${it.path}:check" })
}
