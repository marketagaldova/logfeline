package logfeline.adb

import logfeline.utils.io.*
import logfeline.utils.result.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest


data class LogEntry(val header: Header, val payload: Payload) {
    override fun toString() = "$header\n\t${payload.toString().prependIndent().replace("\n", "\n\t")}"
    
    
    // See https://android.googlesource.com/platform/system/logging/+/refs/heads/main/liblog/include/log/log_read.h#39
    @JvmInline value class Header internal constructor(internal val data: ByteBuffer) {
        val payloadLength get() = data.getShort(0).toUShort()
        val headerLength get() = data.getShort(2).toUShort()
        val pid get() = data.getInt(4)
        val tid get() = data.getInt(8).toUInt()
        val seconds get() = data.getInt(12).toUInt()
        val nanos get() = data.getInt(16).toUInt()
        val lid get() = data.getInt(20).toUInt()
        val uid get() = data.getInt(24).toUInt()

        val timestamp get() = Instant.fromEpochSeconds(seconds.toLong(), nanos.toInt())
        
        override fun toString() = "[$timestamp $pid:$tid lid=$lid uid=$uid header=$headerLength payload=$payloadLength]"
        
        companion object {
            internal const val SIZE = 28
        }
    }
    
    class Payload(internal val data: ByteArray) {
        val priority get() = data.getOrNull(0)?.let { Priority.entries.getOrNull(it.toInt()) } ?: Priority.UNKNOWN
        
        private val tagLength: Int by lazy {
            if (data.size <= 1) return@lazy 0
            for (i in 1 ..< data.size)
                if (data[i] == 0.toByte()) return@lazy i - 1
            data.size - 1
        }
        
        val tag by lazy { if (data.size <= 1) "" else String(data, 1, tagLength) }
        val message by lazy {
            if (tagLength > data.size - 2) return@lazy ""
            if (data.last() == 0.toByte()) String(data, tagLength + 2, data.size - tagLength - 3)
            else String(data, tagLength + 2, data.size - tagLength - 2)
        }

        val tagSum: Long by lazy {
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(data, 1, tagLength)
            val sum = md5.digest()
            ByteBuffer.wrap(sum).getLong()
        }

        override fun toString() = "$priority/$tag: $message"
    }
    
    enum class Priority {
        UNKNOWN, DEFAULT, VERBOSE, DEBUG, INFO, WARN, ERROR, FATAL, SILENT
    }
}


internal suspend fun InputStream.readLog(output: SendChannel<LogEntry>): IOError = withContext(Dispatchers.IO) {
    try { while (true) {
        val entry = readEntry().getOrElse { return@withContext it }
        output.send(entry)
    } }
    finally { output.close() }
    @Suppress("UNREACHABLE_CODE")
    error("Unreachable")
}

private fun InputStream.readEntry(): Result<LogEntry, IOError> {
    val header = readHeader().forwardFailure { return it }
    val payload = readPayload(header.payloadLength.toInt()).forwardFailure { return it }
    return Result.success(LogEntry(header, payload))
}

private fun InputStream.readHeader(): Result<LogEntry.Header, IOError> {
    val buffer = ByteBuffer.allocate(LogEntry.Header.SIZE).order(ByteOrder.LITTLE_ENDIAN)
    readFully(buffer).forwardFailure { return it }
    val header = LogEntry.Header(buffer)
    if (header.headerLength.toInt() < LogEntry.Header.SIZE)
        return Result.failure(IllegalLogHeaderError("Header length ${header.headerLength} is less than the minimum ${LogEntry.Header.SIZE}"))
    if (header.headerLength.toInt() > LogEntry.Header.SIZE)
        skipExact(header.headerLength.toInt() - LogEntry.Header.SIZE)
        .forwardFailure { return it }
    return Result.success(LogEntry.Header(buffer))
}

private fun InputStream.readPayload(length: Int): Result<LogEntry.Payload, IOError> {
    val buffer = ByteArray(length)
    readFully(buffer).forwardFailure { return it }
    return Result.success(LogEntry.Payload(buffer))
}


data class IllegalLogHeaderError(val message: String) : IOError.Read
