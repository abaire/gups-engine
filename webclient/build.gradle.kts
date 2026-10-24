// Static browser client, bundled into the jar as resources (SPEC §2.2). For now this module
// only packages static files; the TypeScript build is added in M6.
plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.jvm.get())
    }
}
