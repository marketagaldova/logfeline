package logfeline.adb


data class Device(
    val id: String,
    val connectionType: ConnectionType,
    val transportId: ULong,
    val serial: String,
    val brand: String,
    val model: String,
) : Comparable<Device> {
    val label by lazy { if (model.lowercase().startsWith(brand.lowercase())) model else "$brand $model" }
    
    
    enum class ConnectionType { UNKNOWN, TCP, USB }

    override fun compareTo(other: Device) = this.id.compareTo(other.id).takeIf { it != 0 } ?: -this.connectionType.compareTo(other.connectionType)
}
