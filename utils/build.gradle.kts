plugins {
    alias(libs.plugins.kotlin)
    `maven-publish`
}


group = "logfeline"
version = libs.versions.logfeline.get()


kotlin.jvmToolchain(17)
