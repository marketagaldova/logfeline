@file:OptIn(ExperimentalContracts::class)

package logfeline.utils.io

import logfeline.utils.result.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.contracts.*


fun OutputStream.writeFully(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Result<Unit, IOError.Write> =
    attemptWrite { Result.success(write(data, offset, length)) }


fun InputStream.readFully(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Result<Unit, IOError.Read> = attemptRead {
    var total = 0
    while (total < length) {
        val count = read(data, offset + total, length - total)
        if (count < 0) return Result.failure(IOError.Read.EOF)
        total += count
    }
}

fun InputStream.readFully(buffer: ByteBuffer) =
    readFully(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining())

fun InputStream.readExact(count: Int): Result<ByteArray, IOError.Read> {
    val result = ByteArray(count)
    readFully(result).onFailure { e -> return Result.failure(e) }
    return Result.success(result)
}

fun InputStream.skipExact(count: Int): Result<Unit, IOError.Read> = attemptRead {
    val buffer = ByteArray(minOf(count, 4096))
    var skipped = 0
    while (skipped < count) {
        val read = read(buffer, 0, minOf(count - skipped, buffer.size))
        if (read < 0) return Result.failure(IOError.Read.EOF)
        skipped += read
    }
}


interface IOError {
    interface Read : IOError {
        object EOF : Read
        data class Generic(val cause: IOException) : Read
    }
    
    interface Write : IOError {
        data class Generic(val cause: IOException) : Write
    }
}

inline fun <R> attemptWrite(block: () -> R): Result<R, IOError.Write> {
    contract {
        callsInPlace(block, kotlin.contracts.InvocationKind.EXACTLY_ONCE)
    }
    
    return attempt(block) catch { e -> IOError.Write.Generic(e as? IOException ?: throw e) }
}

inline fun <R> attemptRead(block: () -> R): Result<R, IOError.Read> {
    contract {
        callsInPlace(block, kotlin.contracts.InvocationKind.EXACTLY_ONCE)
    }

    return attempt(block) catch { e -> IOError.Read.Generic(e as? IOException ?: throw e) }
}
