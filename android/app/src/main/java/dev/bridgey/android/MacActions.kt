package dev.bridgey.android

import org.json.JSONArray
import org.json.JSONObject

// Mac actions (docs/protocol.md, "Mac actions"). The Mac publishes the entries this phone may run
// in `mac_actions.state`; the phone sends back only an identifier from that list (and a 0...100
// level for `volume_set`). It never sends a command, a path, a name or arguments.

internal enum class MacActionKind { SYSTEM, VOLUME, APP, SHORTCUT }

internal object MacActionIds {
    const val LOCK = "lock"
    const val DISPLAY_SLEEP = "display_sleep"
    const val SLEEP = "sleep"
    const val SCREENSAVER = "screensaver"
    const val VOLUME_DOWN = "volume_down"
    const val VOLUME_MUTE = "volume_mute"
    const val VOLUME_UP = "volume_up"
    const val VOLUME_SET = "volume_set"

    val SYSTEM = listOf(LOCK, DISPLAY_SLEEP, SLEEP, SCREENSAVER)
    val VOLUME = listOf(VOLUME_DOWN, VOLUME_MUTE, VOLUME_UP, VOLUME_SET)
    private val HANDLE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val APP = Regex("^app:$HANDLE$")
    private val SHORTCUT = Regex("^shortcut:$HANDLE$")

    fun kind(id: String): MacActionKind? = when {
        id in SYSTEM -> MacActionKind.SYSTEM
        id in VOLUME -> MacActionKind.VOLUME
        APP.matches(id) -> MacActionKind.APP
        SHORTCUT.matches(id) -> MacActionKind.SHORTCUT
        else -> null
    }
}

internal data class MacActionEntry(val id: String, val title: String) {
    val kind: MacActionKind get() = MacActionIds.kind(id) ?: MacActionKind.SYSTEM
}

data class MacVolume(val level: Int, val muted: Boolean)

internal data class MacActionsState(val entries: List<MacActionEntry> = emptyList(), val volume: MacVolume? = null) {
    fun has(id: String): Boolean = entries.any { it.id == id }
    val system: List<MacActionEntry> get() = entries.filter { it.kind == MacActionKind.SYSTEM }
    val apps: List<MacActionEntry> get() = entries.filter { it.kind == MacActionKind.APP }
    val shortcuts: List<MacActionEntry> get() = entries.filter { it.kind == MacActionKind.SHORTCUT }

    companion object {
        const val MAX_ENTRIES = 48
    }
}

/** A shown title: 1 to 64 characters, not blank, no control or line separator characters. */
internal fun validMacActionTitle(value: Any?): String? {
    val text = value as? String ?: return null
    if (text.isBlank() || text.codePointCount(0, text.length) > 64) return null
    if (text.any { Character.getType(it) == Character.CONTROL.toInt() || it == '\u2028' || it == '\u2029' }) return null
    return text
}

internal fun validMacVolume(level: Any?, muted: Any?): MacVolume? =
    if (level is Int && level in 0..100 && muted is Boolean) MacVolume(level, muted) else null

/**
 * Builds the state from already extracted values. Null for a malformed state (wrong version, no
 * list, more than 48 entries). Single entries with an unknown or repeated identifier or a bad title
 * are dropped; a bad volume is dropped.
 */
internal fun macActionsState(version: Any?, entries: List<Pair<Any?, Any?>>?, volume: MacVolume?): MacActionsState? {
    if (version != 1 || entries == null || entries.size > MacActionsState.MAX_ENTRIES) return null
    val seen = HashSet<String>()
    val valid = entries.mapNotNull { (rawId, rawTitle) ->
        val id = rawId as? String ?: return@mapNotNull null
        val title = validMacActionTitle(rawTitle) ?: return@mapNotNull null
        if (MacActionIds.kind(id) == null || !seen.add(id)) null else MacActionEntry(id, title)
    }
    return MacActionsState(valid, volume)
}

internal fun parseMacActionsState(payload: JSONObject): MacActionsState? {
    val array = payload.opt("actions") as? JSONArray
    val entries = array?.let { list ->
        (0 until list.length()).map { index ->
            val item = list.opt(index) as? JSONObject
            item?.opt("id") to item?.opt("title")
        }
    }
    return macActionsState(payload.opt("version"), entries, parseMacVolume(payload.opt("volume")))
}

internal fun parseMacVolume(value: Any?): MacVolume? =
    (value as? JSONObject)?.let { validMacVolume(it.opt("level"), it.opt("muted")) }

/** The phone's own title for fixed identifiers, so they follow the phone's language. */
internal fun macActionTitleRes(id: String): Int? = when (id) {
    MacActionIds.LOCK -> R.string.mac_action_lock
    MacActionIds.DISPLAY_SLEEP -> R.string.mac_action_display_sleep
    MacActionIds.SLEEP -> R.string.mac_action_sleep
    MacActionIds.SCREENSAVER -> R.string.mac_action_screensaver
    MacActionIds.VOLUME_DOWN -> R.string.mac_action_volume_down
    MacActionIds.VOLUME_MUTE -> R.string.mac_action_mute
    MacActionIds.VOLUME_UP -> R.string.mac_action_volume_up
    MacActionIds.VOLUME_SET -> R.string.mac_action_volume
    else -> null
}

/** The status shown for a Mac action result. Unknown reasons read as a failure. */
internal fun macActionResultRes(accepted: Boolean, reason: Any?): Int = if (accepted) R.string.mac_action_done else when (reason) {
    "not_allowed" -> R.string.mac_action_refused_not_allowed
    "invalid" -> R.string.mac_action_refused_invalid
    "rate_limited" -> R.string.mac_action_refused_rate_limited
    "busy" -> R.string.mac_action_refused_busy
    "permission_required" -> R.string.mac_action_refused_permission
    "unavailable" -> R.string.mac_action_refused_unavailable
    else -> R.string.mac_action_refused_failed
}
