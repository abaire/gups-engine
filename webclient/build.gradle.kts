// The browser client: plain JavaScript modules and one CSS file, packaged
// into the jar unchanged as resources. There is no build step and no Node.
plugins { `java-library` }

java { toolchain { languageVersion = JavaLanguageVersion.of(libs.versions.jvm.get()) } }
