import org.gradle.internal.hash.Hashable
import org.gradle.internal.hash.Hashing
import java.util.Properties
import kotlin.concurrent.thread

plugins {
    alias(libs.plugins.kotlin)
    `maven-publish`
}


group = "logfeline"
version = libs.versions.logfeline.get()


kotlin.jvmToolchain(17)

dependencies {
    api(project(":utils"))
    implementation(project(":adb-proto"))
    api(libs.bundles.kotlinx.coroutines)
    api(libs.kotlinx.datetime)
}


tasks.create("build-app-labels-dex") {
    inputs.file(project.layout.projectDirectory.file("AppLabels.java"))
    outputs.file(project.layout.projectDirectory.file("src/main/resources/AppLabels.dex"))
    outputs.file(project.layout.projectDirectory.file("src/main/resources/AppLabels.sha256"))

    doFirst {
        val sdkPath = File(run {
            val properties = Properties()
            rootProject.layout.projectDirectory.file("local.properties").asFile.let {
                if (it.exists()) it.inputStream().use { properties.load(it) }
            }
            (properties["sdk.dir"] as? String) ?: error("""
                Path to the android sdk is required to build app labels dex.
                It may be specified in local.properties in the root project as sdk.dir.
            """.trimIndent())
        })
        val sdkJarPath = sdkPath.resolve("platforms").listFiles()
            ?.filter { it.name.startsWith("android-") }
            ?.maxByOrNull { it.name.removePrefix("android-").toIntOrNull() ?: -1 }
            ?.resolve("android.jar")
            ?.takeIf { it.exists() }
            ?: error("The android sdk is required to build app labels dex, but none was found in ${sdkPath.resolve("platforms")}")
        val d8Path = sdkPath.resolve("build-tools").listFiles()
            ?.maxByOrNull { it.name.split(".").fold(0) { acc, item -> acc * 1000 + (item.toIntOrNull() ?: 0) } }
            ?.resolve("d8")
            ?.takeIf { it.exists() }
            ?: error("d8 is required to build app labels dev, but none was found in ${sdkPath.resolve("build-tools")}")

        val classesDir = temporaryDir.resolve("classes")

        fun ProcessBuilder.startWithIO(): Process {
            redirectOutput(ProcessBuilder.Redirect.PIPE)
            redirectError(ProcessBuilder.Redirect.PIPE)
            val process = start()
            thread { process.inputStream.copyTo(System.out) }
            thread { process.errorStream.copyTo(System.err) }
            return process
        }

        ProcessBuilder(
            "javac",
            "-cp", sdkJarPath.absolutePath,
            "-d", classesDir.absolutePath,
            project.layout.projectDirectory.file("AppLabels.java").asFile.absolutePath,
        ).run {
            val exitCode = startWithIO().waitFor()
            if (exitCode != 0) error("javac exited with a non-zero exit code: $exitCode")
        }
        ProcessBuilder(
            listOf(
                d8Path.absolutePath,
                "--output", temporaryDir.absolutePath,
            ) + (classesDir.listFiles()?.filter { it.name.endsWith(".class") }?.map { it.absolutePath } ?: emptyList())
        ).run {
            val exitCode = startWithIO().waitFor()
            if (exitCode != 0) error("d8 exited with a non-zero exit code: $exitCode")
        }

        val dexFile = temporaryDir.resolve("classes.dex")
        val hash = run {
            val hasher = Hashing.sha256().newHasher()
            dexFile.inputStream().buffered().use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    hasher.putBytes(buffer, 0, count)
                }
            }
            hasher.hash().toCompactString()
        }

        val outputDir = project.layout.projectDirectory.file("src/main/resources").asFile
        dexFile.copyTo(
            target = outputDir.resolve("AppLabels.dex"),
            overwrite = true,
        )
        outputDir.resolve("AppLabels.sha256").writeText(hash)
    }
}
