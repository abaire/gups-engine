plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
}
