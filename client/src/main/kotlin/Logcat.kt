package logfeline.client

import com.github.ajalt.mordant.rendering.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.table.ColumnWidth
import com.github.ajalt.mordant.table.horizontalLayout
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Text
import kotlinx.coroutines.*
import logfeline.adb.LogEntry.Priority
import logfeline.client.cli.Colors
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*
import logfeline.adb.*
import logfeline.client.cli.TextInputHandler
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


suspend fun Terminal.logcat(
    client: AdbClient,
    deviceId: String, deviceLabel: String,
    app: AppPackage,
    appLabelService: AppLabelService = client.startAppLabelService(deviceId),
): Nothing = coroutineScope {
    val clientConfig = ClientConfig.get()

    val appLabel = MutableStateFlow(app.id).apply {
        launch { value = appLabelService.get(app.id) }
    }

    val device = MutableStateFlow<DeviceDescriptor?>(null)
    val pid = MutableStateFlow<Int?>(null)

    val showFilter = MutableStateFlow(false)
    val filterInput = TextInputHandler(this, clientConfig.filter(deviceId, app.id)) { char -> when (char) {
        '\n' -> {
            showFilter.value = false
            moveCursorToEnd()
        }
        else -> null
    } }

    val filter = filterInput.map { if (it.value.isNotBlank()) Filter.parse(it.value) else null }.stateIn(this)

    var tagColors = TagColors.DIM

    var commandOutput: ((String) -> Unit)? = null
    val showCommand = MutableStateFlow(false)
    val commandInput = TextInputHandler(this) { char -> when (char) {
        '\n' -> {
            when {
                value == "save filter" -> {
                    clientConfig.updateFilter(deviceId, app.id, filterInput.value.value)
                    commandOutput?.invoke("Saved filter `${filterInput.value.value}`")
                }
                value.startsWith("highlight ") -> {
                    val style = value.removePrefix("highlight ").uppercase()
                    val newStyle = TagColors.entries.firstOrNull { it.name == style }
                    tagColors = newStyle ?: tagColors
                    if (newStyle != null) commandOutput?.invoke("Set tag highlighting style to ${newStyle.name}")
                    else commandOutput?.invoke("Unknown highlighting style: `$style`")
                }
                value.isNotBlank() -> commandOutput?.invoke("Unknown command: `$value`")
            }
            showCommand.value = false
            clear()
        }
        else -> null
    } }

    @Suppress("NAME_SHADOWING") 
    val statusBar = combine(
        device, pid, appLabel,
        combine(showFilter, filterInput) { show, input -> show to input },
        combine(showCommand, commandInput) { show, input -> show to input },
    ) { device, pid, appLabel, (showFilter, filterInput), (showCommand, commandInput) ->
        when {
            showCommand -> Text(commandInput.render("Command: ", info.width), width = info.width)
            showFilter -> Text(filterInput.render("Set filters", info.width), width = info.width)
            else -> prepareStatusBar(
                deviceLabel, device?.connectionType, device?.state ?: Device.State.Offline,
                appLabel, pid,
                filtered = filterInput.value.isNotBlank(),
            )
        }
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

    // Filter editing
    //TODO: Make this cancellable somehow?
    launch(Dispatchers.IO) { while (currentCoroutineContext().isActive) {
        when {
            showFilter.value -> filterInput.consume()
            showCommand.value -> commandInput.consume()
            else -> when (val char = Char(System.`in`.read())) {
                'q' -> exitProcess(0)
                '/' -> showFilter.value = true
                ':' -> showCommand.value = true
                else -> { /* pass */ }
            }
        }
    } }

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

    commandOutput = { message -> launch { printEvent(Text(
        text = "\n" + (Colors.veryDarkRed on Colors.pink)(message.prependIndent(" ")) + "\n",
        width = info.width,
        align = TextAlign.LEFT,
        whitespace = Whitespace.PRE_WRAP,
    )) } }

    // Actual log
    try {
        client.logcat(deviceId).collect { event -> when (event) {
            is AdbClient.LogcatEvent.Disconnected -> { device.value = event.device }
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
                    if (
                        entry.header.uid != app.uid
                        || (filter.value?.matches(entry.payload.priority, entry.payload.tag) == false)
                    ) continue
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
                    printEvent(formatLogEntry(entry, tagColors))
                }
            }
        } }
        // The logcat flow never ends, but there is no way to notate that.
        awaitCancellation()
    }
    finally {
        filterInput.close()
        commandInput.close()
    }
}


private fun prepareStatusBar(
    device: String, connectionType: Device.ConnectionType?, state: Device.State,
    app: String, pid: Int?,
    filtered: Boolean,
) = horizontalLayout {
    style =
        if (state !is Device.State.Online) Colors.veryDarkRed on Colors.red
        else Colors.veryDarkGreen on Colors.green

    column(0) { this.width = ColumnWidth.Expand() }
    cell(
        " " + bold(app)
        + when {
            connectionType == null && !filtered -> ""
            !filtered -> italic(" (${pid ?: "dead"})")
            connectionType == null -> italic(" (filtered)")
            else -> italic(" (${pid ?: "dead"}, filtered)")
        }
    ) {
        align = TextAlign.LEFT
        overflowWrap = OverflowWrap.ELLIPSES
    }
    column(1) { this.width = ColumnWidth.Expand() }
    val error = (state as? Device.State.Other)?.run { ", $description" } ?: ""
    cell(bold(device) + " " + italic("(${connectionType?.toString()?.lowercase() ?: "offline"}$error)" + " ")) {
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


private data class Filter(
    val excludeTags: List<Tag> = emptyList(),
    val includeTags: List<Tag> = emptyList(),
    val priorities: Set<Priority> = Priority.entries.toSet(),
) {
    fun matches(priority: Priority, tag: String): Boolean =
        priority in priorities
        && excludeTags.none { it.matches(tag) }
        && (includeTags.isEmpty() || includeTags.any { it.matches(tag) })


    companion object {
        fun parse(rawFilter: String): Filter {
            val excludeTags = mutableListOf<Tag>()
            val includeTags = mutableListOf<Tag>()
            val includePriorities = mutableSetOf<Priority>()
            val excludePriorities = mutableSetOf<Priority>()

            rawFilter.split(Regex("\\s+")).forEach { part ->
                when {
                    part.startsWith("tag:") -> part.substring(4).takeIf { it.isNotBlank() }?.let { includeTags.add(Tag.Matches(it)) }
                    part.startsWith("-tag:") -> part.substring(5).takeIf { it.isNotBlank() }?.let { excludeTags.add(Tag.Matches(it)) }
                    part.startsWith("tag.contains:") -> part.substring(13).takeIf { it.isNotBlank() }?.let { includeTags.add(Tag.Contains(it)) }
                    part.startsWith("-tag.contains:") -> part.substring(14).takeIf { it.isNotBlank() }?.let { excludeTags.add(Tag.Contains(it)) }
                    part.startsWith("priority:") -> part.substring(9).split(',')
                        .filter { it.isNotBlank() }
                        .mapNotNull { Priority.entries.firstOrNull { priority -> priority.name.startsWith(it.uppercase()) } }
                        .let { includePriorities.addAll(it) }
                    part.startsWith("-priority:") -> part.substring(10).split(',')
                        .filter { it.isNotBlank() }
                        .mapNotNull { Priority.entries.firstOrNull { priority -> priority.name.startsWith(it.uppercase()) } }
                        .let { excludePriorities.addAll(it) }
                    else -> { /* pass */ }
                }
            }

            return Filter(excludeTags, includeTags, priorities = includePriorities.ifEmpty { Priority.entries.toSet() } - excludePriorities)
        }
    }


    sealed interface Tag {
        fun matches(tag: String): Boolean

        data class Matches(val query: String) : Tag {
            override fun matches(tag: String) = tag == query
        }

        data class Contains(val query: String) : Tag {
            override fun matches(tag: String) = query in tag
        }
    }
}
