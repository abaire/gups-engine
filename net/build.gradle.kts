plugins {
    id("gups.kotlin-module")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.network)
}
