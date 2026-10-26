// The browser client (SPEC §4.10): plain JavaScript modules and one CSS file, packaged
// into the jar unchanged as resources (SPEC §2.2). There is no build step and no Node (SPEC §4.10).
plugins { `java-library` }

java { toolchain { languageVersion = JavaLanguageVersion.of(libs.versions.jvm.get()) } }
