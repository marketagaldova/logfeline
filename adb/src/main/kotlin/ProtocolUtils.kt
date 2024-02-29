package logfeline.adb

import logfeline.utils.io.*
import logfeline.utils.result.*
import java.io.InputStream
import java.io.OutputStream


internal fun OutputStream.writeHex4(value: UShort) = attemptWrite {
    for (shift in 12 downTo 0 step 4) {
        val digit = (value.toInt() ushr shift) and 0b1111
        write(when (digit) {
            in 0..9 -> digit + 48 // 0-9
            else -> digit + 97 - 10 // a-f
        })
    }
}

internal fun OutputStream.writeHex4Prefixed(data: ByteArray): Result<Unit, IOError.Write> {
    if (data.size > UShort.MAX_VALUE.toInt()) return Result.failure(Hex4ValueOutOfRange)
    writeHex4(data.size.toUShort())
    return writeFully(data)
}
internal object Hex4ValueOutOfRange : IOError.Write


internal fun InputStream.readHex4(): Result<UShort, IOError.Read> { return attemptRead {
    var result: UShort = 0u
    for (shift in 12 downTo 0 step 4) {
        val data = read()
        if (data < 0) return Result.failure(IOError.Read.EOF)
        val digit = when (data) {
            in 48..57 -> data - 48 // 0-9
            in 97..102 -> data - 97 + 10 // a-f
            in 65..70 -> data - 65 + 10 // A-F
            else -> return Result.failure(MalformedHex4Input(data))
        }
        result = result or (digit shl shift).toUShort()
    }
    return Result.success(result)
} }
internal data class MalformedHex4Input(val char: Int) : IOError.Read
