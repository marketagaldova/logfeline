package logfeline.adb

import com.android.server.adb.protos.DevicesProto
import logfeline.utils.io.*
import logfeline.utils.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


internal class AdbServerConnection(private val socket: Socket) : Closeable {
    private val scope = CoroutineScope(Dispatchers.IO)
    override fun close() {
        scope.cancel()
        socket.close()
    }
    
    private val inputStream = socket.getInputStream()
    private val outputStream = socket.getOutputStream()
    
    
    private suspend fun sendCommand(command: String): Result<Unit, IOError.Write> = withContext(Dispatchers.IO) {
        outputStream.writeHex4Prefixed(command.toByteArray())
    }
    private suspend fun awaitResponse(): Result<Unit, Error.AdbResponse> = withContext(Dispatchers.IO) {
        val status = inputStream.readExact(4)
            .getOrElse { e -> return@withContext Result.failure(Error.IO.Error(e)) }
            .let { String(it) }
        when (status) {
            "OKAY" -> Result.success()
            "FAIL" -> Result.failure(Error.AdbResponse.FailureResponse)
            else -> Result.failure(Error.AdbResponse.MalformedResponse(status))
        }
    }
    
    
    suspend fun trackDevices(): Result<ReceiveChannel<DevicesProto.Devices>, Error.TrackDevices> {
        sendCommand("host:track-devices-proto-binary").onFailure { e -> return Result.failure(Error.IO.Error(e)) }
        awaitResponse().onFailure { e -> return Result.failure(e) }
        socket.soTimeout = 0
        val channel = Channel<DevicesProto.Devices>(Channel.CONFLATED)
        scope.launch { readProtoIntoChannel(channel, DevicesProto.Devices::parseFrom) }
        return Result.success(channel)
    }
    
    
    suspend fun switchToDevice(serial: String): Result<Unit, Error.SwitchToDevice> {
        sendCommand("host:transport:$serial").onFailure { e -> return Result.failure(Error.IO.Error(e)) }
        awaitResponse().onFailure { e -> return Result.failure(e) }
        return Result.success()
    }
    
    suspend inline fun <R> runShellCommand(
        vararg arguments: String,
        socketTimeout: Duration? = null,
        crossinline block: suspend (OutputStream, InputStream) -> Result<R, IOError>,
    ): Result<R, Error.RunShellCommand> {
        @Suppress("NAME_SHADOWING")
        val arguments = arguments.map {
            if ('"' in it) return Result.failure(Error.IllegalArgument(it))
            "\"$it\""
        }
        sendCommand("shell:command ${arguments.joinToString(" ")}")
            .onFailure { e -> return Result.failure(Error.IO.Error(e)) }
        awaitResponse().onFailure { e -> return Result.failure(e) }
        socket.soTimeout = socketTimeout?.inWholeMilliseconds?.toInt() ?: 0
        return withContext(Dispatchers.IO) { attemptRead {
            block(outputStream, inputStream).getOrElse { e -> return@withContext Result.failure(Error.IO.Error(e)) }
        }.mapError { e -> Error.IO.Error(e) } }
    }


    suspend fun enterSyncMode(socketTimeout: Duration? = null): Result<Unit, Error.Sync> {
        sendCommand("sync:").onFailure { e -> return Result.failure(Error.IO.Error(e)) }
        awaitResponse().onFailure { e -> return Result.failure(e) }
        socket.soTimeout = socketTimeout?.inWholeMilliseconds?.toInt() ?: 0
        return Result.success(Unit)
    }
    private suspend fun sendSyncCommand(command: String, length: UInt): Result<Unit, IOError.Write> = withContext(Dispatchers.IO) {
        outputStream.writeFully(command.toByteArray()).onFailure { e -> return@withContext Result.failure(e) }
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(length.toInt())
        outputStream.writeFully(buffer.array(), buffer.arrayOffset(), 4)
    }
    private suspend fun awaitSyncResponse(
        buffer: ByteBuffer = ByteBuffer.allocate(8),
    ): Result<Pair<String, UInt>, IOError.Read> = withContext(Dispatchers.IO) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.clear()
        buffer.limit(8)
        inputStream.readFully(buffer).forwardFailure { return@withContext it }
        val response = String(buffer.array(), buffer.arrayOffset(), 4)
        val length = buffer.getInt(4).toUInt()
        Result.success(response to length)
    }
    private suspend fun awaitSyncOk(): Result<Unit, Error.Sync> = result {
        val (response) = awaitSyncResponse().getOrFail(Error.IO::Error)
        when (response) {
            "OKAY" -> { /* pass */ }
            "FAIL" -> fail(Error.AdbResponse.FailureResponse)
            else -> fail(Error.AdbResponse.MalformedResponse(response))
        }
    }

    suspend fun exitSyncMode(): Result<Unit, Error.Sync> = result {
        sendSyncCommand("QUIT", 0u).getOrFail(Error.IO::Error)
        // QUIT doesn't seem to have any confirmation
    }

    suspend fun syncList(path: String): Result<List<DirectoryEntry>, Error.Sync> = withContext(Dispatchers.IO) { result {
        val pathBytes = path.toByteArray()
        sendSyncCommand("LIST", pathBytes.size.toUInt()).getOrFail(Error.IO::Error)
        outputStream.writeFully(pathBytes).getOrFail(Error.IO::Error)

        val result = mutableListOf<DirectoryEntry>()
        val protocolBuffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        while (true) {
            val (response, length) = awaitSyncResponse(protocolBuffer).getOrFail(Error.IO::Error)
            when (response) {
                "DENT" -> {
                    protocolBuffer.clear()
                    inputStream.readFully(protocolBuffer).getOrFail(Error.IO::Error)
                    result.add(DirectoryEntry(
                        mode = length,
                        size = protocolBuffer.getInt().toUInt(),
                        lastModified = protocolBuffer.getInt().toUInt(),
                        name = inputStream.readExact(protocolBuffer.getInt())
                            .getOrFail(Error.IO::Error)
                            .let { String(it) },
                    ))
                }
                "DONE" -> {
                    // For whatever reason there are still the 12 bytes after DONE, so we just throw them away
                    inputStream.skipExact(12).getOrFail(Error.IO::Error)
                    break
                }
                "FAIL" -> fail(Error.AdbResponse.FailureResponse)
                else -> fail(Error.AdbResponse.MalformedResponse(response))
            }
        }
        return@result result
    } }
    data class DirectoryEntry(
        val mode: UInt,
        val size: UInt,
        val lastModified: UInt,
        val name: String,
    )

    suspend fun syncSend(
        remotePath: String,
        data: InputStream,
        chunkSize: UShort = 4096u,
    ): Result<Unit, Error.Sync> = withContext(Dispatchers.IO) { result {
        val mode = 0b111_111_111u // adbd sets this if we set u+x anyway, so whatever
        val argument = "$remotePath,$mode".toByteArray()
        sendSyncCommand("SEND", argument.size.toUInt()).getOrFail(Error.IO::Error)
        outputStream.writeFully(argument).getOrFail(Error.IO::Error)

        val buffer = ByteArray(chunkSize.toInt())
        while (true) {
            val count = attemptRead { data.read(buffer, 0, buffer.size) }.getOrFail(Error.IO::Error)
            if (count < 0) break
            sendSyncCommand("DATA", count.toUInt()).getOrFail(Error.IO::Error)
            attemptWrite { outputStream.write(buffer, 0, count) }.getOrFail(Error.IO::Error)
        }
        sendSyncCommand("DONE", (System.currentTimeMillis() / 1000).toUInt()).getOrFail(Error.IO::Error)
        awaitSyncOk().getOrFail()
    } }

    suspend fun syncStat(path: String): Result<StatResult, Error.Sync> = withContext(Dispatchers.IO) { result {
        val pathBytes = path.toByteArray()
        sendSyncCommand("STAT", pathBytes.size.toUInt())
        outputStream.writeFully(pathBytes).getOrFail(Error.IO::Error)
        val (response, length) = awaitSyncResponse().getOrFail(Error.IO::Error)
        when (response) {
            "STAT" -> { /* pass */ }
            "FAIL" -> fail(Error.AdbResponse.FailureResponse)
            else -> fail(Error.AdbResponse.MalformedResponse(response))
        }
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        attemptWrite { inputStream.readFully(buffer) }.getOrFail(Error.IO::Error)
        StatResult(
            mode = length,
            size = buffer.getInt().toUInt(),
            lastModified = buffer.getInt().toUInt(),
        )
    } }
    data class StatResult(
        val mode: UInt,
        val size: UInt,
        val lastModified: UInt,
    ) {
        val exists get() = mode != 0u || size != 0u || lastModified != 0u
    }


    @OptIn(DelicateCoroutinesApi::class)
    private suspend inline fun <T> readProtoIntoChannel(channel: SendChannel<T>, parse: ByteBuffer.() -> T) {
        var buffer = ByteBuffer.allocate(4096)
        while (!channel.isClosedForSend) {
            val messageLength = inputStream.readHex4().getOrElse { channel.close(); return }.toInt()
            if (channel.isClosedForSend) break
            if (messageLength > buffer.capacity()) buffer = ByteBuffer.allocate(buffer.capacity() * 2)
            else buffer.clear()
            buffer.limit(messageLength)
            inputStream.readFully(buffer).onFailure { channel.close(); return }
            channel.send(parse(buffer))
        }
    }

    
    companion object {
        private val SOCKET_TIMEOUT = 6.seconds

        suspend fun open(host: String, port: Int): Result<AdbServerConnection, Error.Connect> = withContext(Dispatchers.IO) {
            attempt {
                AdbServerConnection(Socket(host, port).apply { soTimeout = SOCKET_TIMEOUT.inWholeMilliseconds.toInt() })
            } catch { e -> when (e) {
                is UnknownHostException -> Error.UnknownHost(host)
                is IllegalArgumentException -> Error.InvalidPort(port)
                is IOException -> Error.IO.Exception(e)
                else -> throw e
            } }
        }
    }
    
    
    sealed interface Error {
        sealed interface Connect : Error
        sealed interface TrackDevices : Error
        sealed interface SwitchToDevice : Error
        sealed interface RunShellCommand : Error
        sealed interface TrackApps : Error
        
        sealed interface Sync : Error

        data class UnknownHost(val host: String) : Connect
        data class InvalidPort(val port: Int) : Connect
        sealed interface IO : Error {
            data class Exception(val cause: IOException) : IO, Connect
            data class Error(val cause: IOError) : IO, AdbResponse, TrackDevices, SwitchToDevice, RunShellCommand, TrackApps, Sync
        }
        
        sealed interface AdbResponse : Error, TrackDevices, SwitchToDevice, RunShellCommand, TrackApps, Sync {
            data object FailureResponse : AdbResponse
            data class MalformedResponse(val response: String) : AdbResponse
        }
        
        data class IllegalArgument(val argument: String) : RunShellCommand
    }
}
