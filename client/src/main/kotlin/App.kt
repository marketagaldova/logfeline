package logfeline.client

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.rendering.TextColors.*
import logfeline.adb.AdbClient
import logfeline.adb.AppPackage
import logfeline.adb.Device
import logfeline.client.cli.*
import logfeline.utils.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logfeline.adb.AppLabelService
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds


fun main(args: Array<String>): Unit = runBlocking {
    var adbHost = AdbClient.DEFAULT_HOST
    var adbPort = System.getenv("ANDROID_ADB_SERVER_PORT")?.toIntOrNull() ?: AdbClient.DEFAULT_PORT
    args.forEach { arg -> println(arg); when {
        arg.startsWith("server=") -> {
            val value = arg.split('=', limit = 2)[1].ifBlank {
                println("Missing value for server")
                exitProcess(1)
            }
            val split = value.split(':', limit = 2)
            adbHost = split[0].let { when {
                it.isEmpty() -> adbHost
                it.isBlank() -> {
                    println("Not a valid hostname: `$it`")
                    exitProcess(1)
                }
                else -> it
            } }
            adbPort = if (split.size == 1) AdbClient.DEFAULT_PORT else split[1].toIntOrNull() ?: run {
                println("Invalid port number: `${split[1]}`")
                exitProcess(1)
            }
        }
        else -> {
            println("Unkown argument: `$arg`")
            exitProcess(1)
        }
    } }

    val terminal = Terminal()
    Runtime.getRuntime().addShutdownHook(thread(start = false) { terminal.cursor.move { clearScreenAfterCursor() } })
    switchStdinToDirectMode()
    terminal.cursor.hide(showOnExit = true)

    val client = AdbClient(adbHost, adbPort)

    val (selectedDeviceId, selectedDeviceLabel) = terminal.deviceSelectionMenu(client)
    val appLabelService = client.startAppLabelService(selectedDeviceId)
    appLabelService.cacheAll() // Prefetch the labels because why not
    val selectedApp = terminal.appSelectionMenu(client, selectedDeviceId, appLabelService)
    terminal.logcat(client, selectedDeviceId, selectedDeviceLabel, selectedApp, appLabelService)
}


private data class SelectableDevice(
    val id: String,
    val label: String,
    val connections: List<Device.ConnectionType>,
) : Comparable<SelectableDevice> {
    override fun compareTo(other: SelectableDevice) = this.id.compareTo(other.id)
}

private suspend fun Terminal.deviceSelectionMenu(client: AdbClient): SelectableDevice {
    val result = singleChoiceMenu(
        choices = flow {
            val progressState = ProgressDotsState()
            suspend fun Terminal.emptyHeader() {
                print((bold + white)("Waiting for devices"))
                progressDotsWhileActive(progressState)
            }
            fun Terminal.normalHeader() { print((bold + Colors.blue)("Select a device:")) }

            emit(SingleChoiceMenuState(emptyList(), header = { emptyHeader() }))

            var currentDevices = emptyMap<String, SelectableDevice>()
            client.devices
                .map { newDevices ->
                    if (currentDevices.isEmpty() && newDevices.isEmpty()) { return@map SingleChoiceMenuState(emptyList(), header = { emptyHeader() }) }
                    val newMap = newDevices.groupBy { it.id }
                    currentDevices = currentDevices
                        .mapValues { (id, device) ->
                            device.copy(connections = newMap[id]?.map { it.connectionType }?.sortedDescending() ?: emptyList())
                        }
                    currentDevices = currentDevices + newMap.asSequence().mapNotNull { (id, devices) ->
                        if (id in currentDevices) null
                        else id to SelectableDevice(id, label = devices.first().label, connections = devices.map { it.connectionType }.sortedDescending())
                    }
                    SingleChoiceMenuState(currentDevices.values.sorted(), header = { normalHeader() })
                }
                .let { emitAll(it) }
        },
        key = { it.id },
        label = { device ->
            if (device.connections.isEmpty()) "${(device.label.searchable(dim + white))} ${(dim + italic)("(${"offline".searchable()})")}"
            else ("${device.label.searchable(bold + white)} ${(italic)("(${device.connections.joinToString(", ") { it.name.lowercase().searchable() }})")}")
        },
        searchText = { device ->
            if (device.connections.isEmpty()) "${device.label} offline"
            else "${device.label} ${device.connections.joinToString(" ") { it.name.lowercase() }}"
        },
    )
    println((Colors.blue + bold)("Using device '${result.label}'"))
    return result
}


private suspend fun Terminal.appSelectionMenu(client: AdbClient, deviceId: String, appLabelService: AppLabelService): AppPackage {
    data class SelectableApp(val app: AppPackage, val label: String? = null)

    val (selectedApp, selectedAppLabel) = singleChoiceMenu(
        choices = flow {
            val progressState = ProgressDotsState()
            suspend fun Terminal.emptyHeader() {
                print((bold + white)("Listing installed apps"))
                progressDotsWhileActive(progressState)
            }
            suspend fun Terminal.failedHeader() {
                print((bold + brightRed)("Failed to list installed apps!") + (bold + white)(" Retrying"))
                progressDotsWhileActive(progressState)
            }
            suspend fun Terminal.waitingForLabelsHeader() { while (true) {
                currentCoroutineContext().ensureActive()
                cursor.move { startOfLine(); clearLineAfterCursor() }
                print((bold + Colors.blue)("Select an app "))
                print(italic("(waiting for labels"))
                progressDots(progressState)
                print(italic(")"))
                delay(50.milliseconds)
            } }
            fun Terminal.normalHeader() { print((bold + Colors.blue)("Select an app:")) }

            emit(SingleChoiceMenuState(emptyList(), header = { emptyHeader() }))

            val apps: MutableMap<String, SelectableApp>
            while (true) {
                apps = client.listInstalledPackages(deviceId)
                    .getOrElse {
                        emit(SingleChoiceMenuState(emptyList(), header = { failedHeader() }))
                        null
                    }
                    ?.associateTo(mutableMapOf()) { it.id to SelectableApp(it) }
                    ?: continue
                break
            }

            coroutineScope {
                emit(SingleChoiceMenuState(apps.values.sortedBy { it.app.id }, header = { waitingForLabelsHeader() }))

                // Fetch labels for the debuggable apps first
                val labelChannel = Channel<Pair<String, String>>()
                apps.values
                    .filter { it.app.debuggable }
                    .map { (app) -> launch {
                        val label = appLabelService.get(app.id)
                        labelChannel.send(app.id to label)
                    } }
                    .let { jobs -> launch {
                        jobs.joinAll()
                        labelChannel.close()
                    } }
                for ((appId, label) in labelChannel) {
                    apps[appId] = apps[appId]?.copy(label = label) ?: continue
                    emit(SingleChoiceMenuState(apps.values.sortedBy { it.app.id }, header = { waitingForLabelsHeader() }))
                }
            }

            // Fetch labels for all apps - we do this in a single update to avoid updating the ui too much and causing lots of flickering...
            coroutineScope {
                val mutex = Mutex(locked = true)
                apps.values.map { (app) -> launch {
                    val label = appLabelService.get(app.id)
                    mutex.withLock { apps[app.id] = apps[app.id]?.copy(label = label) ?: return@withLock }
                } }
                mutex.unlock()
            }
            // A final update with all the labels
            emit(SingleChoiceMenuState(apps.values.sortedBy { it.app.id }, header = { normalHeader() }))
        },
        key = { it.app.id },
        hide = { !it.app.debuggable },
        label = { (app, label) ->
            if (label.isNullOrBlank() || label == app.id) app.id.searchable()
            else label.searchable(white + bold) + (dim)(" (${app.id.searchable()})")
        },
        searchText = { (app, label) -> if (label.isNullOrBlank() || label == app.id) app.id else "$label ${app.id}" },
    )

    println((Colors.blue + bold)("Using app '${selectedAppLabel ?: selectedApp.id}'"))
    return selectedApp
}
