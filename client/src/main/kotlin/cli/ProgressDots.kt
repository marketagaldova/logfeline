package logfeline.client.cli

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.rendering.TextStyles.*
import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource


data class ProgressDotsState(val startedAt: TimeMark = TimeSource.Monotonic.markNow(), val duration: Duration = 1.7.seconds)

fun Terminal.progressDots(state: ProgressDotsState) {
    val progress = ((state.startedAt.elapsedNow() / state.duration).let { it - it.toInt() } * 13).toInt()
    fun printDot(offset: Int) {
        @Suppress("NAME_SHADOWING")
        val progress = (progress + offset) % 13
        print(when (progress) {
            in 0..2 -> " "
            in 3..5 -> (dim)(".")
            in 6..9 -> (bold)(".")
            in 10..12 -> (dim)(".")
            else -> " "
        })
    }
    printDot(2)
    printDot(1)
    printDot(0)
}

suspend fun Terminal.progressDotsWhileActive(state: ProgressDotsState = ProgressDotsState()): Nothing { withContext(Dispatchers.Default) {
    while (true) {
        currentCoroutineContext().ensureActive()
        cursor.move { savePosition() }
        progressDots(state)
        cursor.move { restorePosition() }
        delay(state.duration / 13)
    }
} }
