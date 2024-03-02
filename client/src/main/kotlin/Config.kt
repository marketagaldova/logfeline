package logfeline.client

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.*


@Serializable data class ClientConfig(
    private val filters: MutableMap<String, MutableMap<String, String>> = mutableMapOf()
) {
    fun filter(deviceId: String, packageId: String) = filters[deviceId]?.get(packageId) ?: ""
    fun updateFilter(deviceId: String, packageId: String, filter: String) { synchronized(Companion) {
        filters.getOrPut(deviceId, ::mutableMapOf)[packageId] = filter
        save()
    } }
    
    private fun save() {
        if (!configFile.exists())
            configFile.createParentDirectories(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        @OptIn(ExperimentalSerializationApi::class)
        configFile.outputStream().use { json.encodeToStream(this, it) }
    }
    
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; coerceInputValues = true }
        
        private var instance: ClientConfig? = null
        fun get(): ClientConfig {
            instance?.let { return it }
            synchronized(Companion) {
                instance?.let { return it }
                val configFiles = listOf(configFile) + (System.getenv("XDG_CONFIG_DIRS") ?: "/etc/xdg").split(':').map { Path(it) / "logfeline" / "client-config.json" }
                val actualConfigFile = configFiles.firstOrNull { it.exists() } ?: configFiles.first()
                if (!actualConfigFile.exists()) return ClientConfig()
                @OptIn(ExperimentalSerializationApi::class)
                return actualConfigFile.inputStream().use { json.decodeFromStream<ClientConfig>(it) }.also { instance = it }
            }
        }
        
        private val configFile: Path =
            (System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { Path(it) / "logfeline" / "client-config.json" })
            ?: (Path(System.getProperty("user.home")) / ".config" / "logfeline" / "client-config.json")
    }
}
