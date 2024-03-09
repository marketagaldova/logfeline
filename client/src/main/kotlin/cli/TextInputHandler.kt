package logfeline.client.cli

import com.github.ajalt.mordant.rendering.TextStyles.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.InputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


class TextInputHandler(
    scope: CoroutineScope,
    initialValue: String = "",
    private val blinkInterval: Duration? = 0.75.seconds,
    private val customHandler: State.(Char) -> State? = { null },
) : StateFlow<TextInputHandler.State> {
    private val flow = MutableStateFlow(State(initialValue))
    override val replayCache get() = flow.replayCache
    override val value get() = flow.value
    override suspend fun collect(collector: FlowCollector<State>) = flow.collect(collector)
    
    init {
        if (blinkInterval != null) scope.launch { while (isActive) {
            delay(blinkInterval)
            synchronized(this) { flow.value = flow.value.blink() }
        } }
    }
    
    fun consume(stream: InputStream = System.`in`) = synchronized(this) { flow.value.run {
        val char = Char(stream.read())
        customHandler(char)?.let { flow.value = it; return }
        flow.value = when (char) {
            ESCAPE -> {
                if (Char(stream.read()) != '[') return
                when (Char(stream.read())) {
                    'C' -> moveCursor(1) // Right arrow
                    'D' -> moveCursor(-1) // Left arrow
                    'H' -> moveCursorToStart() // Home
                    'F' -> moveCursorToEnd() // End
                    '3' -> when (Char(stream.read())) {
                        '~' -> delete() // Delete
                        else -> return
                    }
                    else -> return
                }
            }
            '\n' -> return // Enter
            '\u007f' -> backspace() // Backspace
            else -> insert(char)
        }
    } }
    
    
    data class State(val value: String = "", val cursorPosition: Int = value.length, val blinkState: Boolean = true) {
        fun blink() = copy(blinkState = !blinkState)
        
        fun insert(char: Char) = when {
            cursorPosition <= 0 -> State("$char$value", 1)
            cursorPosition >= value.length -> State("$value$char")
            else -> State(
                value = buildString(value.length + 1) {
                    append(value, 0, cursorPosition)
                    append(char)
                    append(value, cursorPosition, value.length)
                },
                cursorPosition = cursorPosition + 1,
            )
        }
        
        fun backspace() = when {
            cursorPosition <= 0 -> this
            cursorPosition >= value.length -> State(value.dropLast(1))
            else -> State(
                value = buildString(value.length - 1) {
                    if (cursorPosition > 1) append(value, 0, cursorPosition - 1)
                    append(value, cursorPosition, value.length)
                },
                cursorPosition = cursorPosition - 1,
            )
        }
        
        fun delete() = when {
            cursorPosition >= value.length -> this
            cursorPosition <= 0 -> State(value.drop(1), 0)
            else -> State(
                value = buildString(value.length - 1) {
                    append(value, 0, cursorPosition)
                    if (cursorPosition + 1 < value.length) append(value, cursorPosition + 1, value.length)
                },
                cursorPosition = cursorPosition,
            )
        }
        
        fun moveCursor(distance: Int) = when {
            cursorPosition <= 0 -> if (distance < 0) this else State(value, distance)
            cursorPosition >= value.length -> if (distance > 0) this else State(value, value.length + distance)
            else -> State(value, cursorPosition + distance)
        }
        
        fun moveCursorToStart() = if (cursorPosition <= 0) this else State(value, 0)
        fun moveCursorToEnd() = if (cursorPosition >= value.length) this else State(value)
        
        fun clear() = State()
        
        
        fun render(title: String, width: Int): String = buildString(width + 128) {
            append(title)
            append(": ")
            
            fun padding(extraLength: Int) { append(underline(" ".repeat(maxOf(0, width - title.length - 2 - value.length - extraLength)))) }
            
            when {
                !blinkState -> {
                    append(underline(value))
                    padding(0)
                }
                cursorPosition >= value.length -> {
                    append(underline(value))
                    append((underline + inverse)(" "))
                    padding(1)
                }
                else -> {
                    append(underline(value.substring(0, cursorPosition)))
                    append((underline + inverse)(value[cursorPosition].toString()))
                    if (cursorPosition + 1 <= value.lastIndex) append(underline(value.substring(cursorPosition + 1)))
                    padding(0)
                }
            }
        }
    }
}
