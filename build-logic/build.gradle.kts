plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
}
