plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinx.serialization)
    application
}


group = "logfeline"
version = libs.versions.logfeline.get()


kotlin.jvmToolchain(17)
application.mainClass = "logfeline.client.AppKt"


dependencies {
    implementation(project(":adb"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mordant)
}
