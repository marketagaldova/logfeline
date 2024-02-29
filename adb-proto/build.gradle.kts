plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.protobuf)
}


group = "logfeline"
version = libs.versions.logfeline.get()


kotlin.jvmToolchain(17)

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.runtime.get()}" }
    generateProtoTasks { all().forEach { task ->
        task.builtins { create("kotlin") }
    } }
}

dependencies {
    api(libs.bundles.protobuf.runtime)
}
