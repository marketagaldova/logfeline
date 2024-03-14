package logfeline.adb


data class Device(
    val id: String,
    override val connectionType: ConnectionType,
    override val state: State.Online,
    override val transportId: ULong,
    override val serial: String,
    val brand: String,
    val model: String,
) : DeviceDescriptor {
    override val estimatedId: String get() = id
    val label by lazy { if (model.lowercase().startsWith(brand.lowercase())) model else "$brand $model" }
    
    override fun compareTo(other: DeviceDescriptor) = when (other) {
        !is Device -> -1
        else -> this.id.compareTo(other.id).takeIf { it != 0 } ?: -this.connectionType.compareTo(other.connectionType)
    }

    
    enum class ConnectionType { UNKNOWN, TCP, USB }

    sealed interface State {
        data object Online : State
        data object Offline : State
        data class Other(val description: String) : State
    }
}

data class OfflineDevice(
    override val connectionType: Device.ConnectionType,
    override val state: Device.State,
    override val transportId: ULong,
    override val serial: String,
    override val estimatedId: String?,
) : DeviceDescriptor {
    override fun compareTo(other: DeviceDescriptor) = when (other) {
        is Device -> 1
        else -> this.transportId.compareTo(other.transportId)
    }
}


sealed interface DeviceDescriptor : Comparable<DeviceDescriptor> {
    val connectionType: Device.ConnectionType
    val state: Device.State
    val transportId: ULong
    val serial: String
    val estimatedId: String?
}
