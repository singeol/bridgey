package dev.bridgey.android

import java.text.Normalizer
import java.util.Locale

/**
 * Wire rules for folder transfers (docs/protocol.md, "Folders"). Pure Kotlin so
 * the checks and the receiver's local plan run in JVM unit tests.
 */
internal object FolderRules {
    const val MAX_FILES = 10_000
    const val MAX_FOLDERS = 2_000
    const val MAX_DEPTH = 32
    const val MAX_NAME_BYTES = 255
    const val MAX_PATH_BYTES = 1_024
    const val MAX_FILE_SIZE = 10L * 1024 * 1024 * 1024
    const val MAX_TOTAL_SIZE = 1L shl 40
    const val MAX_PAGES = 64
    const val MAX_ENTRIES_PER_PAGE = 256
    const val MAX_PAYLOAD_BYTES = 32 * 1024

    /**
     * Local names stay well below the 255-byte file system limit, leaving room
     * for a conflict number and the prefix MediaStore adds to pending files.
     */
    const val MAX_LOCAL_NAME_BYTES = 200

    /** One path component as sent by a peer: never empty, `.`, `..`, a separator or a control character. */
    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            utf8Length(name) <= MAX_NAME_BYTES &&
            name.none { it == '/' || it.code < 0x20 || it.code == 0x7f }

    /** A relative path inside the folder: valid components joined by `/`, bounded in depth and length. */
    fun isValidPath(path: String): Boolean {
        if (path.isEmpty() || utf8Length(path) > MAX_PATH_BYTES) return false
        val components = path.split('/')
        return components.size <= MAX_DEPTH && components.all(::isValidName)
    }
}

internal data class FolderEntry(val path: String, val isDirectory: Boolean, val size: Long = 0) {
    val name: String get() = path.substringAfterLast('/')
    val parent: String get() = path.substringBeforeLast('/', "")
}

internal data class FolderOffer(
    val folderId: String,
    val name: String,
    val fileCount: Int,
    val folderCount: Int,
    val totalSize: Long,
    val pageCount: Int,
)

internal enum class FolderRejection(val code: String) {
    POLICY("policy"),
    UNSUPPORTED("unsupported"),
    BUSY("busy"),
    INVALID_MANIFEST("invalid_manifest"),
    LIMIT_EXCEEDED("limit_exceeded"),
    INSUFFICIENT_SPACE("insufficient_space"),
    IO_ERROR("io_error"),
}

/** Checks an offer before its pages arrive. Returns null when the receiver may collect the manifest. */
internal fun validateFolderOffer(offer: FolderOffer): FolderRejection? = when {
    !FolderRules.isValidName(offer.name) -> FolderRejection.INVALID_MANIFEST
    offer.pageCount !in 1..FolderRules.MAX_PAGES -> FolderRejection.INVALID_MANIFEST
    offer.fileCount < 1 || offer.folderCount < 0 || offer.totalSize < 0 -> FolderRejection.INVALID_MANIFEST
    offer.fileCount > FolderRules.MAX_FILES || offer.folderCount > FolderRules.MAX_FOLDERS ||
        offer.totalSize > FolderRules.MAX_TOTAL_SIZE -> FolderRejection.LIMIT_EXCEEDED
    else -> null
}

/**
 * Checks a complete manifest against its offer. Every entry must have a valid,
 * unique path whose parent is the folder itself or a directory listed earlier,
 * so the tree is well formed before anything is written. Returns null when valid.
 */
internal fun validateFolderManifest(offer: FolderOffer, entries: List<FolderEntry>): FolderRejection? {
    validateFolderOffer(offer)?.let { return it }
    val paths = HashSet<String>()
    val directories = HashSet<String>()
    var files = 0
    var folders = 0
    var total = 0L
    for (entry in entries) {
        if (!FolderRules.isValidPath(entry.path) || !paths.add(entry.path)) return FolderRejection.INVALID_MANIFEST
        if (entry.parent.isNotEmpty() && entry.parent !in directories) return FolderRejection.INVALID_MANIFEST
        if (entry.isDirectory) {
            if (entry.size != 0L) return FolderRejection.INVALID_MANIFEST
            directories += entry.path
            folders++
        } else {
            if (entry.size < 0) return FolderRejection.INVALID_MANIFEST
            if (entry.size > FolderRules.MAX_FILE_SIZE) return FolderRejection.LIMIT_EXCEEDED
            files++
            total += entry.size
        }
        if (files > FolderRules.MAX_FILES || folders > FolderRules.MAX_FOLDERS || total > FolderRules.MAX_TOTAL_SIZE) {
            return FolderRejection.LIMIT_EXCEEDED
        }
    }
    if (files != offer.fileCount || folders != offer.folderCount || total != offer.totalSize) {
        return FolderRejection.INVALID_MANIFEST
    }
    return null
}

/**
 * Local relative paths for a received folder. The folder is always saved as a
 * new folder, so nothing existing is merged or replaced. Names are made safe for
 * shared storage, and a sibling whose safe name matches an earlier one ignoring
 * case gets the first free number: `photo (1).jpg`, `Album (1)`.
 */
internal class FolderPlan private constructor(private val localPaths: List<String>) {
    fun localPath(entry: Int): String = localPaths[entry]

    companion object {
        fun build(entries: List<FolderEntry>): FolderPlan {
            val localPaths = ArrayList<String>(entries.size)
            val localDirectories = HashMap<String, String>()
            val takenByParent = HashMap<String, MutableSet<String>>()
            val nextNumber = HashMap<String, Int>()
            entries.forEach { entry ->
                val parent = if (entry.parent.isEmpty()) "" else localDirectories.getValue(entry.parent)
                val taken = takenByParent.getOrPut(parent, ::HashSet)
                val safe = safeLocalName(entry.name)
                var name = safe
                if (name.lowercase(Locale.ROOT) in taken) {
                    // Remember where numbering stopped so many clashing siblings stay linear.
                    val key = parent + "\u0000" + safe.lowercase(Locale.ROOT)
                    var number = nextNumber[key] ?: 1
                    do {
                        name = numberedName(safe, number++, entry.isDirectory)
                    } while (name.lowercase(Locale.ROOT) in taken)
                    nextNumber[key] = number
                }
                taken += name.lowercase(Locale.ROOT)
                val path = if (parent.isEmpty()) name else "$parent/$name"
                if (entry.isDirectory) localDirectories[entry.path] = path
                localPaths += path
            }
            return FolderPlan(localPaths)
        }
    }
}

/**
 * A name that is safe on FAT-style shared storage: NFC, no reserved or control
 * characters, no leading spaces or trailing spaces and dots, and at most
 * [FolderRules.MAX_LOCAL_NAME_BYTES] UTF-8 bytes with the extension kept.
 */
internal fun safeLocalName(name: String): String {
    val replaced = Normalizer.normalize(name, Normalizer.Form.NFC).map { char ->
        if (char.code < 0x20 || char.code == 0x7f || char in "\"*/:<>?\\|") '_' else char
    }.joinToString("")
    val trimmed = replaced.trimStart(' ').trimEnd(' ', '.').ifEmpty { "_" }
    if (utf8Length(trimmed) <= FolderRules.MAX_LOCAL_NAME_BYTES) return trimmed
    val dot = trimmed.lastIndexOf('.')
    val extension = if (dot > 0 && trimmed.length - dot <= 16) trimmed.substring(dot) else ""
    val stem = truncateUtf8(trimmed.dropLast(extension.length), FolderRules.MAX_LOCAL_NAME_BYTES - utf8Length(extension))
    return (stem.trimEnd(' ', '.').ifEmpty { "_" }) + extension
}

/** [name] when it is free, otherwise the first free numbered name. */
internal fun uniqueLocalName(name: String, isDirectory: Boolean, taken: (String) -> Boolean): String? {
    if (!taken(name)) return name
    return (1..9_999).asSequence().map { numberedName(name, it, isDirectory) }.firstOrNull { !taken(it) }
}

/** `Album (2)` for folders, `photo (2).jpg` for files. */
internal fun numberedName(name: String, number: Int, isDirectory: Boolean): String {
    val dot = name.lastIndexOf('.')
    return if (!isDirectory && dot > 0) "${name.substring(0, dot)} ($number)${name.substring(dot)}" else "$name ($number)"
}

internal fun utf8Length(value: String): Int = value.toByteArray(Charsets.UTF_8).size

private fun truncateUtf8(value: String, maxBytes: Int): String {
    val out = StringBuilder()
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val size = utf8Length(String(Character.toChars(codePoint)))
        if (bytes + size > maxBytes) break
        out.appendCodePoint(codePoint)
        bytes += size
        index += Character.charCount(codePoint)
    }
    return out.toString()
}
