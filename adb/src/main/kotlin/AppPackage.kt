package logfeline.adb


data class AppPackage(
    val id: String,
    val uid: UInt,
    val flags: Set<String>,
) {
    val debuggable get() = "DEBUGGABLE" in flags
}
