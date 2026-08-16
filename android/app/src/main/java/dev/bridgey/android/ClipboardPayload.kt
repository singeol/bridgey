package dev.bridgey.android

import org.json.JSONObject

internal const val MAX_CLIPBOARD_CONTENT_BYTES = 32 * 1024

internal data class RichClipboardContent(val text: String, val html: String) {
    fun encode(): ByteArray = JSONObject()
        .put("version", 1)
        .put("text", text)
        .put("html", html)
        .toString()
        .toByteArray(Charsets.UTF_8)

    companion object {
        fun create(text: String, html: String?): RichClipboardContent? {
            val rich = html?.takeIf(String::isNotBlank) ?: return null
            if (text.isEmpty() || text.toByteArray().size + rich.toByteArray().size > MAX_CLIPBOARD_CONTENT_BYTES) return null
            return RichClipboardContent(text, rich)
        }

        fun decode(bytes: ByteArray): RichClipboardContent? = runCatching {
            val objectValue = JSONObject(bytes.toString(Charsets.UTF_8))
            if (objectValue.optInt("version") != 1 || objectValue.opt("text") !is String || objectValue.opt("html") !is String) return null
            create(objectValue.getString("text"), objectValue.getString("html"))
        }.getOrNull()
    }
}

internal fun clipboardTextFits(text: String): Boolean =
    text.isNotEmpty() && text.toByteArray(Charsets.UTF_8).size <= MAX_CLIPBOARD_CONTENT_BYTES
