plugins {
    id("gups.kotlin-module")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.netty.handler)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.kotlinx.serialization.json)
}
