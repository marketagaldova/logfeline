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


val fatJar = tasks.register<Jar>("fatJar") {
    dependsOn("compileJava", "compileKotlin", "processResources")

    manifest.attributes("Main-Class" to application.mainClass)
    archiveBaseName.set("logfeline")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } + sourceSets.main.get().output)
}

val shebangifyJar = tasks.register("shebangifyJar") {
    dependsOn(fatJar)
    val sourceJar = fatJar.get().outputs.files.single()
    val outputFile = layout.buildDirectory.file("bin/logfeline")
    outputs.file(outputFile)
    doFirst {
        outputFile.get().asFile.outputStream().use { output ->
            output.write("#!/bin/env -S java -jar\n".toByteArray())
            sourceJar.inputStream().use { it.copyTo(output) }
        }
        outputFile.get().asFile.setExecutable(true, false)
    }
}

tasks.getByName("build").dependsOn(fatJar, shebangifyJar)
