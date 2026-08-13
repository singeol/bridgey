package dev.bridgey.core.discovery

data class DiscoveredPeer(
    val serviceName: String,
    val deviceIdHint: String?,
    val deviceNameHint: String,
    val platformHint: String?,
    val protocolVersionHint: Int?,
    val host: String?,
    val port: Int?,
) {
    val key: String get() = serviceName
}

object DiscoveryTxtRecord {
    private const val MAX_ID_BYTES = 36
    private const val MAX_NAME_BYTES = 64
    private const val MAX_VERSION_BYTES = 8
    private const val MAX_PLATFORM_BYTES = 16

    fun parse(serviceName: String, attributes: Map<String, ByteArray>): DiscoveredPeer {
        val id = decode(attributes["id"], MAX_ID_BYTES)?.takeIf(::isUuid)
        val name = decode(attributes["name"], MAX_NAME_BYTES)
            ?.takeIf { it.isNotBlank() }
            ?: serviceName.take(64)
        val platform = decode(attributes["platform"], MAX_PLATFORM_BYTES)
            ?.takeIf { it.matches(Regex("[a-z][a-z0-9-]{0,15}")) }
        val version = decode(attributes["version"], MAX_VERSION_BYTES)
            ?.toIntOrNull()
            ?.takeIf { it in 1..Int.MAX_VALUE }

        return DiscoveredPeer(serviceName, id, name, platform, version, null, null)
    }

    private fun decode(value: ByteArray?, maxBytes: Int): String? {
        if (value == null || value.isEmpty() || value.size > maxBytes) return null
        return value.toString(Charsets.UTF_8).takeIf { it.toByteArray(Charsets.UTF_8).contentEquals(value) }
    }

    private fun isUuid(value: String): Boolean = runCatching {
        java.util.UUID.fromString(value).toString().equals(value, ignoreCase = true)
    }.getOrDefault(false)
}

interface DiscoveryService {
    val peers: kotlinx.coroutines.flow.StateFlow<List<DiscoveredPeer>>
    fun start()
    fun stop()
}

