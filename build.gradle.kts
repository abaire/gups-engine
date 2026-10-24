// Module dependency rules (SPEC §2.2). The `checkModuleGraph` task fails the build if any
// module declares a project dependency that is not listed here. `cli` is the only module
// that wires everything together, so it is unrestricted.
val allowedModuleDeps: Map<String, Set<String>?> = mapOf(
    ":core" to emptySet(),
    ":script" to setOf(":core"),
    ":persist" to setOf(":core"),
    ":net" to setOf(":core"),
    ":auth" to setOf(":core"),
    ":harness" to setOf(":core", ":script"),
    ":plugin-api" to setOf(":core", ":script"),
    ":webclient" to emptySet(),
    ":cli" to null,
)

evaluationDependsOnChildren()

val declaredModuleDeps: Map<String, Set<String>> = subprojects.associate { p ->
    p.path to p.configurations
        .flatMap { it.dependencies.withType<ProjectDependency>() }
        .map { it.path }
        .filter { it != p.path }
        .toSortedSet()
}

val checkModuleGraph = tasks.register("checkModuleGraph") {
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

tasks.register("check") {
    group = "verification"
    description = "Runs all checks, including the module graph check."
    dependsOn(checkModuleGraph)
    dependsOn(subprojects.map { "${it.path}:check" })
}
