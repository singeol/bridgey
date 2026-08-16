package dev.bridgey.android

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal data class DiagnosticEvent(
    val timestamp: String,
    val category: String,
    val event: String,
    val outcome: String,
)

internal class BridgeyDiagnostics(private val limit: Int = 200) {
    private val events = ArrayDeque<DiagnosticEvent>()

    @Synchronized fun record(category: String, event: String, outcome: String = "ok") {
        events.addLast(DiagnosticEvent(Instant.now().toString(), token(category), token(event), token(outcome)))
        while (events.size > limit) events.removeFirst()
    }

    @Synchronized fun snapshot(): List<DiagnosticEvent> = events.toList()

    fun report(
        context: Context,
        connectionState: String,
        transfers: Collection<FileTransferState>,
        localFeatures: Map<BridgeyFeature, Boolean>,
        remoteFeatures: Map<BridgeyFeature, Boolean>,
    ): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("generatedAt", Instant.now().toString())
            put("appVersion", version)
            put("platform", "android")
            put("osApi", Build.VERSION.SDK_INT)
            put("connectionState", token(connectionState))
            put("activeTransferCount", transfers.count(FileTransferState::active))
            put("historyCount", transfers.count { !it.active })
            put("localFeatures", featureObject(localFeatures))
            put("remoteFeatures", featureObject(remoteFeatures))
            put("events", JSONArray().apply {
                snapshot().forEach { event ->
                    put(JSONObject().apply {
                        put("timestamp", event.timestamp)
                        put("category", event.category)
                        put("event", event.event)
                        put("outcome", event.outcome)
                    })
                }
            })
        }.toString(2)
    }

    private fun featureObject(features: Map<BridgeyFeature, Boolean>) = JSONObject().apply {
        BridgeyFeature.entries.forEach { put(it.key, features[it] != false) }
    }

    private fun token(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9_.-]"), "_")
        .take(64)
}
