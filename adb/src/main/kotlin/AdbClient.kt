package logfeline.adb

import com.android.server.adb.protos.DevicesProto
import logfeline.utils.io.IOError
import logfeline.utils.io.attemptRead
import logfeline.utils.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.awt.image.BufferedImage
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


class AdbClient(
    val host: String = DEFAULT_HOST,
    val port: Int = System.getenv("ANDROID_ADB_SERVER_PORT")?.toIntOrNull() ?: DEFAULT_PORT,
    val ssl: Boolean = false,
    val reconnectInterval: Duration = 10.seconds,
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    
    
    val devices: SharedFlow<List<DeviceDescriptor>> = flow {
        while (currentCoroutineContext().isActive) {
            AdbServerConnection.open(host, port, ssl)
                .onFailure { delay(reconnectInterval) }
                .onSuccess { connection -> connection.use {
                    val channel = connection.trackDevices().getOrElse { return@onSuccess }
                    try { coroutineScope outerScope@{
                        val updateChannel = Channel<List<DevicesProto.Device>?>()
                        launch {
                            try {
                                for (devices in channel)
                                    devices.deviceList
                                        .let { updateChannel.send(it) }
                            }
                            finally { updateChannel.close() }
                        }

                        var currentRawDevices = listOf<DevicesProto.Device>()
                        val pingJobs = mutableMapOf<ULong, PingJob>()

                        try { for (newDevices in updateChannel) coroutineScope {
                            newDevices?.let { newDevices ->
                                currentRawDevices = newDevices
                                // Prune zombie jobs
                                pingJobs.values
                                    .filter { job -> newDevices.none { it.transportId.toULong() == job.transportId } }
                                    .forEach { job ->
                                        job.cancel()
                                        pingJobs.remove(job.transportId)
                                    }
                                // Start jobs for new devices
                                newDevices.filter { it.connectionType != DevicesProto.ConnectionType.USB }
                                    .forEach { device ->
                                        val transportId = device.transportId.toULong()
                                        if (transportId in pingJobs) return@forEach
                                        pingJobs[transportId] = PingJob(this@outerScope, transportId, device.serial, updateChannel)
                                    }
                            }

                            currentRawDevices
                                .map {
                                    val state =
                                        if (pingJobs[it.transportId.toULong()]?.isOnline() == false) Device.State.Offline
                                        else when (it.state!!) {
                                            DevicesProto.ConnectionState.DEVICE -> Device.State.Online
                                            DevicesProto.ConnectionState.OFFLINE -> Device.State.Offline

                                            DevicesProto.ConnectionState.CONNECTING -> Device.State.Other("connecting")
                                            DevicesProto.ConnectionState.AUTHORIZING -> Device.State.Other("authorizing")
                                            DevicesProto.ConnectionState.UNAUTHORIZED -> Device.State.Other("unauthorized")
                                            DevicesProto.ConnectionState.NOPERMISSION -> Device.State.Other("insufficient permissions")

                                            DevicesProto.ConnectionState.DETACHED -> Device.State.Other("detached")
                                            DevicesProto.ConnectionState.BOOTLOADER -> Device.State.Other("bootloader")
                                            DevicesProto.ConnectionState.RECOVERY -> Device.State.Other("recovery")
                                            DevicesProto.ConnectionState.SIDELOAD -> Device.State.Other("sideload")
                                            DevicesProto.ConnectionState.RESCUE -> Device.State.Other("rescue")

                                            DevicesProto.ConnectionState.HOST,
                                            DevicesProto.ConnectionState.ANY,
                                            DevicesProto.ConnectionState.UNRECOGNIZED,
                                            -> Device.State.Other("unrecognized state")
                                        }
                                    async { normalizeDevice(it, state) }
                                }
                                .awaitAll()
                                .sorted()
                                .let { emit(it) }
                        } }
                        finally { pingJobs.values.forEach { it.cancel() } }
                    } }
                    finally {
                        channel.cancel()
                        emit(emptyList())
                    }
                } }
        }
    }
        .flowOn(Dispatchers.IO)
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeout = 10.seconds, replayExpiration = Duration.ZERO), replay = 1)

    private inner class PingJob(
            scope: CoroutineScope,
            val transportId: ULong,
            val serial: String,
            val updateChannel: SendChannel<Nothing?>,
    ) {
        fun cancel() = job.cancel()

        private var init = CompletableDeferred<Unit>()
            .also { scope.launch {
                delay(NON_USB_DEVICE_TIMEOUT)
                it.complete(Unit)
            } }
        private var isOnline = false
            set(value) {
                field = value
                init.complete(Unit)
            }
        suspend fun isOnline(): Boolean {
            init.await()
            return isOnline
        }

        val job = scope.launch {
            try { while (isActive) {
                runShellCommand(
                    serial, "sh", "-c", "while true; do echo; sleep ${NON_USB_DEVICE_PING_INTERVAL.inWholeSeconds}; done",
                    socketTimeout = NON_USB_DEVICE_TIMEOUT,
                ) { _, stdout ->
                    isOnline = true
                    updateChannel.send(null)
                    while (isActive) {
                        val byte =
                            try { stdout.read() }
                            catch (e: SocketTimeoutException) { break }
                            catch (e: IOException) { break  }
                        if (byte < 0) break
                    }
                    Result.success()
                }
                val wasOnline = isOnline
                isOnline = false
                if (wasOnline) updateChannel.send(null)
            } }
            catch (e: ClosedSendChannelException) { /* pass */ }
        }
    }

    fun device(id: String): Flow<DeviceDescriptor?> = flow {
        var lastValue: DeviceDescriptor? = null
        devices.collect { devices ->
            val newValue = devices.firstOrNull { it.estimatedId == id }
            if (newValue?.transportId != lastValue?.transportId || newValue?.state != lastValue?.state) {
                emit(newValue)
                lastValue = newValue
            }
        }
    }.conflate()

    private suspend fun findOnlineDevice(id: String): Device? {
        return (devices.firstOrNull() ?: return null)
            .asSequence()
            .filterIsInstance<Device>()
            .firstOrNull { it.id == id }
    }


    private suspend fun normalizeDevice(proto: DevicesProto.Device, state: Device.State): DeviceDescriptor = coroutineScope {
        val connectionType = when (proto.connectionType!!) {
            DevicesProto.ConnectionType.USB -> Device.ConnectionType.USB
            DevicesProto.ConnectionType.SOCKET -> Device.ConnectionType.TCP
            DevicesProto.ConnectionType.UNKNOWN, DevicesProto.ConnectionType.UNRECOGNIZED -> Device.ConnectionType.UNKNOWN
        }
        fun offline() = OfflineDevice(
            connectionType, state,
            transportId = proto.transportId.toULong(),
            serial = proto.serial,
            estimatedId = when (connectionType) {
                Device.ConnectionType.USB -> "serial:${proto.serial}"
                else -> null
            },
        )

        if (state !is Device.State.Online) return@coroutineScope offline()

        val realSerial = async { getRealDeviceSerial(proto.serial) }
        val brand = async { getDeviceBrand(proto.serial) }
        Device(
            id = "serial:${realSerial.await().getOrNull() ?: return@coroutineScope offline()}",
            connectionType, state,
            transportId = proto.transportId.toULong(),
            serial = proto.serial,
            brand = brand.await().getOrNull() ?: "Unknown Brand",
            model = proto.model,
        )
    }
    
    private suspend fun getRealDeviceSerial(serial: String) = getProp(serial, "ro.serialno", cache = true)
    private suspend fun getDeviceBrand(serial: String) = getProp(serial, "ro.product.brand", cache = true)
    
    //TODO: Add some ttl here
    private val propCache = mutableMapOf<String, MutableMap<String, String>>()
    private suspend fun getProp(serial: String, prop: String, cache: Boolean = false): Result<String, AdbServerConnection.Error> {
        if (cache) synchronized(propCache) {
            propCache[serial]?.get(prop)?.let { return Result.success(it) }
        }
        return runShellCommand(serial, "getprop", prop)
            .mapValue { it.trim() }
            .onSuccess { value -> if (cache) synchronized(propCache) {
                propCache.getOrPut(serial, ::mutableMapOf)[prop] = value 
            } }
    }
    

    suspend fun listInstalledPackages(deviceId: String): Result<List<AppPackage>, Error.ListInstalledPackages> {
        val device = findOnlineDevice(deviceId) ?: return Result.failure(Error.DeviceOffline(deviceId))

        return runShellCommand(
            device.serial, "sh", "-c", "dumpsys package packages | grep -E '^ *Package \\[|^ *userId=|^ *flags=\\['",
        ) { _, stdout ->
            attemptRead { coroutineScope {
                val channel = Channel<String>(Channel.BUFFERED)
                launch(Dispatchers.IO) {
                    stdout.bufferedReader().lineSequence().forEach { channel.send(it.trim()) }
                    channel.close()
                }
                buildList {
                    var id: String? = null
                    var uid: UInt? = null
                    var flags = mutableSetOf<String>()
                    try {
                        for (line in channel) when {
                            line.startsWith("Package [") -> {
                                if (id != null && uid != null) add(AppPackage(id, uid, flags))
                                id = null; uid = null; flags = mutableSetOf()
                                val start = line.indexOf('[')
                                val end = line.indexOf(']')
                                if (start < 0 || end < 0 || end <= start) continue
                                else id = line.substring(start + 1, end)
                            }
                            id == null -> continue
                            line.startsWith("userId=") -> uid = line.removePrefix("userId=").toUIntOrNull()
                            line.startsWith("flags=") -> {
                                val start = line.indexOf('[')
                                val end = line.indexOf(']')
                                if (start < 0 || end < 0 || end <= start) continue
                                flags.addAll(line.substring(start + 1, end).trim().split(" "))
                            }
                            else -> continue
                        }
                    }
                    catch (e: ClosedReceiveChannelException) { /* pass */ }
                    finally { if (id != null && uid != null) add(AppPackage(id, uid, flags)) }
                }
            } }
        }.mapError { Error.IO }
    }

    
    fun startAppLabelService(deviceId: String) = AppLabelService(this, deviceId)

    internal suspend fun prepareAppLabelsDex(serial: String): Result<String, Error.IO> = result {
        val classLoader = this::class.java.classLoader
        val appLabelDexHash = classLoader.getResourceAsStream("AppLabels.sha256")!!.bufferedReader().use { it.readText() }
        val appLabelDexName = "AppLabels-$appLabelDexHash.dex"

        AdbServerConnection.open(host, port, ssl)
            .getOrFail { Error.IO }
            .use { connection ->
                connection.switchToDevice(serial).getOrFail { Error.IO }
                connection.enterSyncMode().getOrFail { Error.IO }

                val dexUpToDate = connection.syncStat("/data/local/tmp/$appLabelDexName")
                    .getOrFail { Error.IO }
                    .exists

                if (!dexUpToDate)
                    classLoader.getResourceAsStream("AppLabels.dex")!!.use { input ->
                        connection.syncSend("/data/local/tmp/$appLabelDexName", input).getOrFail { Error.IO }
                    }

                connection.exitSyncMode().getOrFail { Error.IO }
            }

        "CLASSPATH=/data/local/tmp/$appLabelDexName app_process / AppLabels"
    }


    fun logcat(deviceId: String): Flow<LogcatEvent> = flow {
        var lastEntry: LogEntry? = null
        var disconnectedAt: Instant? = null

        @OptIn(ExperimentalCoroutinesApi::class)
        emitAll(device(deviceId).transformLatest { device ->
            if (device !is Device) {
                disconnectedAt = disconnectedAt ?: Clock.System.now()
                emit(LogcatEvent.Disconnected(disconnectedAt!!, device))
                return@transformLatest
            }
            try {
                val entries = Channel<LogEntry>(Channel.BUFFERED)
                if (disconnectedAt == null) emit(LogcatEvent.Connected.Initial(device, Clock.System.now(), entries))
                else emit(LogcatEvent.Connected.Reconnect(device, disconnectedAt!!, Clock.System.now(), entries))
                disconnectedAt = null

                //TODO: There is *some* potential for this to get stuck.
                //      If the connection fails while waiting for a message, but the device detection is still active,
                //      the reader will wait indefinitely for a read that will never happen. This could perhaps be solved
                //      somewhat by adding a timeout and simply reconnecting when it occurs, but this is not a great fix
                //      as it will also cause reconnects when the log is simply inactive. A solution on top of this one
                //      could be that we start a separate process on the device which will periodically print something
                //      to the log, essentially creating a ping mechanism, keeping the connection alive even if the log
                //      is inactive. Whether or not this is worth it depends on how expensive the reconnects actually
                //      are. Also this will fix itself once the device state changes, as that will cause a new emission
                //      into the device flow and thus restart this part anyway.
                //      There is a similar potential with device tracking, but since that only talks to the adb server,
                //      which is usually on localhost, chances of that connection failing are tiny.
                //TODO: For the above TODO, maybe the v2 shell could help? I need to look into that.

                try { while (currentCoroutineContext().isActive) {
                    val startTime = lastEntry?.let { "${it.header.seconds}.${it.header.nanos}" } ?: "0.0"
                    runShellCommand(device.serial, "logcat", "--binary", "-T", startTime) { _, stdout -> coroutineScope {
                        val passThroughChannel = Channel<LogEntry>()
                        launch {
                            try { for (entry in passThroughChannel) {
                                entries.send(entry)
                                lastEntry = entry
                            } }
                            catch (e: ClosedSendChannelException) { /* pass */ }
                            finally { stdout.close() }
                        }
                        try { stdout.readLog(passThroughChannel) }
                        finally { passThroughChannel.close() }
                        Result.success(Error.IO)
                    } }
                } }
                finally { entries.close() }
            }
            finally { disconnectedAt = Clock.System.now() }
        })
    }
    sealed interface LogcatEvent {
        val device: DeviceDescriptor?

        data class Disconnected(val disconnectedAt: Instant, override val device: DeviceDescriptor?) : LogcatEvent
        sealed interface Connected : LogcatEvent {
            override val device: Device
            val connectedAt: Instant
            val entries: ReceiveChannel<LogEntry>

            data class Initial(
                override val device: Device,
                override val connectedAt: Instant,
                override val entries: ReceiveChannel<LogEntry>,
            ) : Connected

            data class Reconnect(
                override val device: Device,
                val disconnectedAt: Instant,
                override val connectedAt: Instant,
                override val entries: ReceiveChannel<LogEntry>,
            ) : Connected
        }
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchPidOf(deviceId: String, packageId: String) = flow {
        emit(null)
        var previousPid: Int? = null
        device(deviceId).transformLatest { device ->
            if (device?.state !is Device.State.Online) return@transformLatest
            while (true) {
                runShellCommand(
                    device.serial,
                    "sh", "-c", "while true; do pidof '$packageId' || echo 'dead'; sleep 1; done",
                    socketTimeout = 3.seconds,
                    ) { _, stdout ->
                    try {
                        val reader = stdout.bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: throw EOFException()
                            val pid = line.toIntOrNull()
                            if (pid != previousPid) {
                                emit(pid)
                                previousPid = pid
                            }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        Result.success()
                    }
                    catch (e: IOException) { Result.failure(IOError.Read.Generic(e)) }
                }.onFailure { delay(1.seconds) }
            }
        }.let { emitAll(it) }
    }.conflate()


    suspend fun screenshot(deviceId: String): Result<BufferedImage, Error.ScreenShot> {
        val device = findOnlineDevice(deviceId) ?: return Result.failure(Error.DeviceOffline(deviceId))
        val frameBuffer = AdbServerConnection.open(host, port, ssl)
            .getOrElse { return Result.failure(Error.IO) }
            .use { connection ->
                connection.switchToDevice(device.serial).onFailure { return Result.failure(Error.IO) }
                connection.frameBuffer().getOrElse { return Result.failure(Error.IO) }
            }

        val image = BufferedImage(frameBuffer.header.width, frameBuffer.header.height, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 ..< frameBuffer.header.width)
            for (y in 0 ..< frameBuffer.header.height) {
                val i = (y * frameBuffer.header.width + x) * (frameBuffer.header.bpp / 8)
                val pixel =
                    (frameBuffer.data[i + (frameBuffer.header.alphaOffset / 8)].toInt() and 0xff shl 24) or
                    (frameBuffer.data[i + (frameBuffer.header.redOffset / 8)].toInt() and 0xff shl 16) or
                    (frameBuffer.data[i + (frameBuffer.header.greenOffset / 8)].toInt() and 0xff shl 8) or
                    (frameBuffer.data[i + (frameBuffer.header.blueOffset / 8)].toInt() and 0xff)
                image.setRGB(x, y, pixel)
            }
        return Result.success(image)
    }


    @OptIn(ExperimentalContracts::class)
    internal suspend inline fun <R> runShellCommand(
            serial: String,
            vararg arguments: String,
            socketTimeout: Duration? = null,
            crossinline block: suspend (OutputStream, InputStream) -> Result<R, IOError>,
    ): Result<R, AdbServerConnection.Error> {
        contract {
            callsInPlace(block, InvocationKind.AT_MOST_ONCE)
        }

        AdbServerConnection.open(host, port, ssl)
            .getOrElse { return Result.failure(it) }
            .use { connection ->
                connection.switchToDevice(serial).onFailure { e -> return Result.failure(e) }
                connection.runShellCommand(*arguments, socketTimeout = socketTimeout, block = block)
                    .getOrElse { e -> return Result.failure(e) }
                    .let { return Result.success(it) }
            }
    }
    internal suspend fun runShellCommand(serial: String, vararg arguments: String): Result<String, AdbServerConnection.Error> =
        runShellCommand(serial, *arguments) { _, stdout -> Result.success(stdout.bufferedReader().readText()) }


    companion object {
        const val DEFAULT_HOST = "localhost"
        const val DEFAULT_PORT = 5037

        val NON_USB_DEVICE_PING_INTERVAL = 3.seconds
        val NON_USB_DEVICE_TIMEOUT = 6.seconds
    }


    sealed interface Error {
        sealed interface ListInstalledPackages : Error
        sealed interface GetLabelForApp : Error
        sealed interface Logcat : Error
        sealed interface ScreenShot : Error

        data object IO : ListInstalledPackages, GetLabelForApp, Logcat, ScreenShot
        data class DeviceOffline(val deviceId: String) : ListInstalledPackages, GetLabelForApp, Logcat, ScreenShot
    }
}
