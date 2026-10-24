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
    implementation(libs.clikt)
}

application {
    applicationName = "gups"
    mainClass = "gups.cli.MainKt"
    // The terminal library loads JNA; allow it without the JDK 22+ restricted-method warning.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
