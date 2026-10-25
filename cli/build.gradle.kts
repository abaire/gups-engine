import java.lang.module.ModuleDescriptor
import java.lang.module.ModuleFinder
import java.util.zip.ZipFile

plugins {
    id("gups.kotlin-module")
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":script"))
    implementation(project(":persist"))
    implementation(project(":net"))
    implementation(project(":auth"))
    implementation(project(":harness"))
    implementation(project(":plugin-api"))
    runtimeOnly(project(":webclient"))
    runtimeOnly(libs.slf4j.nop)
    implementation(libs.clikt)
    implementation(libs.micrometer.prometheus)
    testImplementation(libs.selenium.java)
}

application {
    applicationName = "gups"
    mainClass = "gups.cli.MainKt"
    // The terminal library loads JNA; allow it without the JDK 22+ restricted-method warning.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// The changelog the HTML reference renders (DocsRenderer.bundledChangelog).
tasks.processResources {
    from(rootProject.file("docs/CHANGELOG.md")) { into("gups/docs") }
}

tasks.test {
    val templateWorld = rootProject.layout.projectDirectory.dir("template-world")
    inputs.dir(templateWorld).withPropertyName("templateWorld")
    systemProperty("gups.templateWorld", templateWorld.asFile.absolutePath)
    // -Dgups.browserTest=true runs the headless Firefox test outside Linux CI too.
    systemProperty("gups.browserTest", System.getProperty("gups.browserTest") ?: "false")
    // The committed api.json the golden test compares with (a test resource, so already an
    // input); -Dgups.updateGolden=true rewrites it.
    systemProperty("gups.apiGolden", layout.projectDirectory.file("src/test/resources/api.golden.json").asFile.absolutePath)
    // The committed HTML reference of a small model (DocsRendererTest).
    systemProperty("gups.docsGolden", layout.projectDirectory.dir("src/test/resources/docs-golden").asFile.absolutePath)
    systemProperty("gups.updateGolden", System.getProperty("gups.updateGolden") ?: "false")
    // The changelog, MIGRATING.md and API baselines the repository check reads (SPEC_MILESTONE7.md §4.4).
    inputs.dir(rootProject.file("docs")).withPropertyName("docs")
    systemProperty("gups.docsDir", rootProject.file("docs").absolutePath)
}

// ---- The HTML API reference (SPEC_MILESTONE7.md §3.2) -----------------------------------

val generatedDocsDir = layout.buildDirectory.dir("generated-docs")

val generateDocs by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Writes the HTML API reference into build/generated-docs."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "gups.cli.MainKt"
    val out = generatedDocsDir
    argumentProviders += CommandLineArgumentProvider { listOf("docs", "--out", out.get().asFile.absolutePath) }
    outputs.dir(generatedDocsDir)
    doFirst { out.get().asFile.deleteRecursively() }
}

distributions {
    main {
        contents {
            from(generateDocs) { into("docs") }
        }
    }
}

val docsArchive by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packs the API reference as build/distributions/gups-docs-<version>.zip."
    archiveFileName = "gups-docs-${project.version}.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    into("gups-docs-${project.version}")
    from(generateDocs)
}

// ---- Release images (M6 design §7.3) -------------------------------------------------------

/** The platform an image is built for, as CI names it: linux-x64, linux-arm64 or macos-arm64. */
val platform: String = (findProperty("platform") as String?) ?: run {
    val os = System.getProperty("os.name").lowercase().let { if ("mac" in it) "macos" else if ("win" in it) "windows" else "linux" }
    val arch = System.getProperty("os.arch").let { if (it == "aarch64" || it == "arm64") "arm64" else "x64" }
    "$os-$arch"
}

val imageDir = layout.buildDirectory.dir("image/gups")

/**
 * A self-contained `gups`: a jlink runtime with just the JDK modules jdeps finds the
 * libraries need, the jars, and a launcher that uses that runtime (D-164).
 */
val jlinkImage by tasks.registering {
    group = "distribution"
    description = "Builds build/image/gups: a jlink runtime, the jars and a bin/gups launcher."
    dependsOn(tasks.named("installDist"))
    val install = layout.buildDirectory.dir("install/gups")
    val jvm = libs.versions.jvm.get()
    val launcher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(jvm) }
    val image = imageDir
    inputs.dir(install)
    outputs.dir(image)
    doLast {
        val javaHome = launcher.get().metadata.installationPath.asFile
        fun run(vararg cmd: String): String {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "${cmd.first()} failed:\n$out" }
            return out.trim()
        }
        val lib = install.get().dir("lib").asFile
        val jars = lib.listFiles()!!.filter { it.name.endsWith(".jar") }.sortedBy { it.name }
        // jdeps can't resolve modular jars whose optional requirements aren't shipped (Netty's), so it
        // reads only the plain jars; modular jars declare what they need in their descriptors.
        val modular = jars.filter { j -> ZipFile(j).use { z -> z.entries().asSequence().any { it.name.endsWith("module-info.class") } } }
        val plain = jars - modular.toSet()
        val fromPlain = run(
            javaHome.resolve("bin/jdeps").path, "--print-module-deps", "--ignore-missing-deps", "--multi-release", jvm,
            "--class-path", jars.joinToString(File.pathSeparator) { it.absolutePath }, *plain.map { it.absolutePath }.toTypedArray(),
        ).lines().last().split(',')
        val system = ModuleFinder.ofSystem().findAll().map { it.descriptor().name() }.toSet()
        val fromModular = modular.flatMap { j ->
            ModuleFinder.of(j.toPath()).findAll().flatMap { m ->
                m.descriptor().requires().filter { ModuleDescriptor.Requires.Modifier.STATIC !in it.modifiers() }.map { it.name() }
            }
        }.filter { it in system }
        val found = fromPlain + fromModular
        // Loaded reflectively or by service lookup, so jdeps can't see them: EC keys, DNS, Unsafe, JMX.
        val extra = listOf("jdk.crypto.ec", "jdk.naming.dns", "jdk.unsupported", "java.management", "java.naming")
        val modules = (found + extra).map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        val out = image.get().asFile
        out.deleteRecursively()
        run(
            javaHome.resolve("bin/jlink").path, "--add-modules", modules.joinToString(","), "--strip-debug", "--no-header-files",
            "--no-man-pages", "--compress=zip-6", "--output", out.resolve("runtime").path,
        )
        lib.copyRecursively(out.resolve("lib"))
        install.get().dir("docs").asFile.copyRecursively(out.resolve("docs"))
        val bin = out.resolve("bin").also { it.mkdirs() }.resolve("gups")
        bin.writeText(
            """
            |#!/bin/sh
            |# The gups launcher of a jlink image (M6 design §7.3): runs the bundled runtime.
            |HERE=${'$'}(cd "${'$'}(dirname "${'$'}0")/.." && pwd)
            |exec "${'$'}HERE/runtime/bin/java" --enable-native-access=ALL-UNNAMED -cp "${'$'}HERE/lib/*" gups.cli.MainKt "${'$'}@"
            |
            """.trimMargin(),
        )
        bin.setExecutable(true)
        out.resolve("modules.txt").writeText(modules.joinToString("\n", postfix = "\n"))
    }
}

/** The image as `build/distributions/gups-<version>-<platform>.tar.gz`, as CI attaches it to releases. */
val jlinkArchive by tasks.registering(Tar::class) {
    group = "distribution"
    description = "Packs the jlink image for one platform."
    dependsOn(jlinkImage)
    archiveFileName = "gups-${project.version}-$platform.tar.gz"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    compression = Compression.GZIP
    into("gups-${project.version}-$platform")
    from(imageDir)
    // Keep the launcher and the runtime's binaries executable.
    filePermissions { unix("rw-r--r--") }
    eachFile { if (path.contains("/bin/")) permissions { unix("rwxr-xr-x") } }
}

val templateDir = layout.buildDirectory.dir("template/course-world-template")

val imageOwner: String = (findProperty("imageOwner") as String?)?.lowercase()
    ?: System.getenv("GITHUB_REPOSITORY_OWNER")?.lowercase()?.takeIf { it.isNotBlank() }
    ?: "gups"

/**
 * Prepares the course world template for release (SPEC_MILESTONE6 §8.1), stamping
 * the engine version pin into MODULE.gups and the image owner and tag into gates.yml.
 */
val prepareCourseWorldTemplate = tasks.register<Sync>("prepareCourseWorldTemplate") {
    group = "distribution"
    description = "Prepares course-world-template with stamped engine version pin and gates workflow."
    val src = rootProject.layout.projectDirectory.dir("template-world")
    val v = project.version.toString()
    val owner = imageOwner
    inputs.property("version", v)
    inputs.property("imageOwner", owner)
    from(src)
    into(templateDir)
    // Stamp MODULE.gups with engine version pin
    filesMatching("MODULE.gups") {
        filter { line ->
            if (line.trim() == "module(") {
                "module(\n    # Pinned engine version (SPEC §2.3, SPEC_MILESTONE6 §8.1).\n    engine = \"$v\",\n"
            } else {
                line
            }
        }
    }
    // Stamp gates.yml image owner and tag with engine publisher and version
    filesMatching(".github/workflows/gates.yml") {
        filter { line ->
            line.replace("ghcr.io/OWNER/gups:VERSION", "ghcr.io/$owner/gups:$v")
        }
    }
}

val courseWorldTemplateArchive = tasks.register<Tar>("courseWorldTemplateArchive") {
    group = "distribution"
    description = "Packs the course-world-template for release."
    dependsOn(prepareCourseWorldTemplate)
    archiveFileName = "course-world-template-${project.version}.tar.gz"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    compression = Compression.GZIP
    into("course-world-template")
    from(templateDir)
}
