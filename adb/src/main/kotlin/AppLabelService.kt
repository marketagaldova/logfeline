package logfeline.adb

import logfeline.utils.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import logfeline.utils.io.IOError
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds


class AppLabelService internal constructor(private val client: AdbClient, private val deviceId: String) : Closeable {
    private val scope = CoroutineScope(Dispatchers.Default)
    
    private val pendingRequests = Channel<String?>()
    private val inFlightRequests = mutableSetOf<String>()
    private val responseFlow = MutableSharedFlow<Pair<String, String>>(replay = 10)
    
    init { scope.launch {
        client.device(deviceId)
            .collectLatest { device ->
                if (device == null) return@collectLatest
                while (currentCoroutineContext().isActive) {
                    val command = client.prepareAppLabelsDex(device.serial).getOrElse { delay(5.milliseconds); null } ?: continue
                    client.runShellCommand(device.serial, "sh", "-c", "$command --serve", socketTimeout = TIMEOUT) { stdin, stdout -> coroutineScope {
                        val writerJob = launch {
                            suspend fun request(packageId: String?) {
                                @Suppress("NAME_SHADOWING")
                                val command = (packageId?.let { "find:$packageId\n" } ?: "list-all\n").toByteArray()
                                while (currentCoroutineContext().isActive)
                                    try { stdin.write(command); break }
                                    catch (e: SocketTimeoutException) { continue }
                                    catch (e: IOException) {
                                        stdout.close()
                                        cancel()
                                        awaitCancellation()
                                    }
                            }
                            
                            // Retry in-flight requests from previous session
                            inFlightRequests.forEach { request(it) }
                            
                            // Handle the incoming requests.
                            for (packageId in pendingRequests) {
                                if (packageId != null) synchronized(inFlightRequests) { inFlightRequests.add(packageId) }
                                request(packageId)
                            }
                        }
                        
                        try {
                            suspend fun respond(packageId: String, label: String) {
                                responseFlow.emit(packageId to label)
                                synchronized(inFlightRequests) { inFlightRequests.remove(packageId) }
                            }
                            
                            val reader = stdout.bufferedReader()
                            while (currentCoroutineContext().isActive) {
                                val line = reader.readLine() ?: break
                                when {
                                    line == "listing" -> while (currentCoroutineContext().isActive) {
                                        val response = reader.readLine()
                                        if (response.isEmpty()) break
                                        response.split(':', limit = 2).let { (packageId, label) -> respond(packageId, label) }
                                    }
                                    
                                    line.startsWith("package:") ->
                                        line.split(':', limit = 3).let { (_, packageId, label) -> respond(packageId, label) }
                                    
                                    line.startsWith("ping:") -> { /* pass */ }
                                    
                                    line.startsWith("error:not-found:") ->
                                        line.substring("error:not-found:".length).let { respond(it, it) }
                                    
                                    else -> return@coroutineScope Result.failure(InvalidResponseError(line))
                                }
                            }
                            Result.success()
                        }
                        catch (e: IOException) { Result.failure(IOError.Read.Generic(e)) }
                        finally { writerJob.cancel() }
                    } }
                }
            }
    } }

    override fun close() {
        scope.cancel()
        pendingRequests.close()
    }
    
    private val cache = ConcurrentHashMap<String, String>().also { cache ->
        scope.launch { responseFlow.collect { (packageId, label) -> cache[packageId] = label } }
    }
    
    suspend fun get(packageId: String): String {
        cache[packageId]?.let { return it }
        // We do this async thing here so that if close is called, this will get cancelled as well. I never actually tested this tho lol
        return scope.async {
            responseFlow
                .onSubscription { pendingRequests.send(packageId) }
                .first { it.first == packageId }
                .second
        }.await()
    }
    
    suspend fun cacheAll() { pendingRequests.send(null) }
    
    data class InvalidResponseError(val command: String) : IOError.Read
    
    companion object {
        private val TIMEOUT = 6.seconds
    }
}
