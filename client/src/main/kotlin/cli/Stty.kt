package logfeline.client.cli

import kotlinx.coroutines.*
import java.io.IOException
import kotlin.concurrent.thread


fun switchStdinToDirectMode() {
    fun stty(vararg arguments: String): String = runBlocking(Dispatchers.IO) {
        val process = ProcessBuilder(listOf("stty", *arguments)).run {
            redirectInput(ProcessBuilder.Redirect.INHERIT)
            start()
        }

        val output = async { process.inputStream.bufferedReader().readText() }
        val errorOutput = async { process.errorStream.bufferedReader().readText() }

        val exitCode = process.waitFor()
        if (exitCode != 0) throw IOException("stty exited with non-zero exit code $exitCode:\n${errorOutput.await()}")

        output.await()
    }

    val savedSttyConfig = stty("-g").trim()
    Runtime.getRuntime().addShutdownHook(thread(start = false) { stty(savedSttyConfig) })
    stty("-icanon", "-echo")
}
