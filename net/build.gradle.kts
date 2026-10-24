plugins {
    id("gups.kotlin-module")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.netty.handler)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.kotlinx.serialization.json)
}
