package logfeline.client

import com.github.ajalt.mordant.rendering.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.table.ColumnWidth
import com.github.ajalt.mordant.table.horizontalLayout
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Text
import logfeline.adb.LogEntry.Priority
import logfeline.client.cli.Colors
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.*
import logfeline.adb.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


suspend fun Terminal.logcat(
    client: AdbClient,
    deviceId: String, deviceLabel: String,
    app: AppPackage,
    appLabelService: AppLabelService = client.startAppLabelService(deviceId),
): Nothing = coroutineScope {
    val appLabel = MutableStateFlow(app.id).apply {
        launch { value = appLabelService.get(app.id) }
    }

    val device = MutableStateFlow<Device?>(null)
    val pid = MutableStateFlow<Int?>(null)

    @Suppress("NAME_SHADOWING") 
    val statusBar = combine(device, pid, appLabel) { device, pid, appLabel ->
        prepareStatusBar(deviceLabel, device?.connectionType, appLabel, pid)
    }.stateIn(this)

    fun renderStatusBar() {
        print(statusBar.value)
        cursor.move { startOfLine() }
    }

    val renderMutex = Mutex()

    // Clear the screen to start with
    cursor.move { clearScreen(); down(info.height) }

    // Status bar updates
    launch { statusBar.collectLatest { renderMutex.withLock { renderStatusBar() } } }

    // Actual log
    suspend fun printEvent(event: String) = renderMutex.withLock {
        cursor.move { up(1) }
        // 0J is clearScreenAfterCursor. We do this part manually so we can print this in one go and avoid
        // flickering of the status bar as much as possible. Unfortunately, this further breaks non-unix
        // copmatibility, but given that we require stty already, who cares...
        val buffer = "\u001b[0J$event\n\n"
        rawPrint(buffer)
        renderStatusBar()
    }
    suspend fun printEvent(event: Widget) = renderMutex.withLock {
        cursor.move { up(1); clearScreenAfterCursor() }
        println(event)
        println()
        renderStatusBar()
    }
    client.logcat(deviceId).collect { event -> when (event) {
        is AdbClient.LogcatEvent.Disconnected -> { device.value = null }
        is AdbClient.LogcatEvent.Connected -> {
            device.value = event.device

            val text = when (event) {
                is AdbClient.LogcatEvent.Connected.Initial ->
                    bold("Connected via ${event.device.connectionType.name.lowercase()}")
                is AdbClient.LogcatEvent.Connected.Reconnect ->
                    "${bold("Reconnected via ${event.device.connectionType.name.lowercase()}")} ${italic("(${(event.connectedAt - event.disconnectedAt).format()} offline)")}"
            }
            printEvent(Text(
                text = "\n" + (Colors.veryDarkRed on Colors.pink)(text) + "\n",
                width = info.width,
                align = TextAlign.CENTER,
            ))

            for (entry in event.entries) {
                if (entry.header.uid != app.uid) continue
                //TODO: Set back to null when the process exits.
                //      This will probably require some polling, though it should be doable as a device-side shell command that will exit once a poll fails.
                if (pid.value != entry.header.pid) {
                    pid.value = entry.header.pid
                    printEvent(Text(
                        text = "\n${(bold + Colors.veryDarkBlue on Colors.blue)("Process ${pid.value}")}\n",
                        width = info.width,
                        align = TextAlign.CENTER,
                    ))
                }
                printEvent(formatLogEntry(entry))
            }
        }
    } }

    awaitCancellation() // The logcat flow never ends, but there is no way to notate that.
}


private fun prepareStatusBar(
    device: String, connectionType: Device.ConnectionType?,
    app: String, pid: Int?,
) = horizontalLayout {
    style =
        if (connectionType == null) Colors.veryDarkRed on Colors.red
        else Colors.veryDarkGreen on Colors.green

    column(0) { this.width = ColumnWidth.Expand() }
    cell(
        " " + bold(app)
        + if (connectionType != null) " " +  italic("(${pid ?: "dead"})") else ""
    ) {
        align = TextAlign.LEFT
        overflowWrap = OverflowWrap.ELLIPSES
    }
    column(1) { this.width = ColumnWidth.Expand() }
    cell(bold(device) + " " + italic("(${connectionType?.toString()?.lowercase() ?: "offline"})" + " ")) {
        align = TextAlign.RIGHT
        overflowWrap = OverflowWrap.ELLIPSES
    }
}

private fun Duration.format(): String = if (this > 1.seconds) inWholeSeconds.seconds.toString() else toString()

private fun formatLogEntry(entry: LogEntry, tagColors: TagColors = TagColors.DIM): String {
    val style = when (entry.payload.priority) {
        Priority.VERBOSE -> Colors.pinkishGray
        Priority.DEBUG -> bold + Colors.blue
        Priority.INFO -> Colors.white
        Priority.WARN -> bold + Colors.yellow
        Priority.ERROR, Priority.FATAL -> bold + Colors.brightRed
        Priority.DEFAULT, Priority.UNKNOWN, Priority.SILENT -> Colors.blue
    }

    val headerText = "${entry.payload.priority.name.first()}/${entry.payload.tag}"
    var messagePrefix = "\t"
    val header =
        if (tagColors == TagColors.NONE) (italic + Colors.offWhite)("$headerText:")
        else {
            val hue = entry.payload.tagSum % 361
            val dim = TextColors.hsl(hue, s = 0.2, l = 0.15)
            val bright = TextColors.hsl(hue, s = 0.33, l = 0.5)

            when (tagColors) {
                TagColors.BASIC -> (italic + bright)("$headerText:")
                TagColors.DIM -> {
                    messagePrefix = (bright on dim)(" ") + "  "
                    (bold + bright on dim)(" $headerText ")
                }
                TagColors.BRIGHT -> {
                    messagePrefix = (dim on bright)(" ") + "  "
                    (bold + dim on bright)(" $headerText ")
                }
                else -> error("Unreachable")
            }
        }

    val message = entry.payload.message.trimEnd()
    if ('\n' !in message) return "$header  ${style(message)}"

    val formattedMessage = style(message.prependIndent(messagePrefix))
    return "$header\n$formattedMessage"
}

enum class TagColors { NONE, BASIC, DIM, BRIGHT }
