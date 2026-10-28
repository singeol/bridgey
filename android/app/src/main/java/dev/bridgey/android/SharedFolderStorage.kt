package dev.bridgey.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** The shared folder is gone: its grant was revoked or its storage removed. */
internal class SharedFolderUnavailableException(cause: Throwable? = null) : Exception(cause)

/**
 * Reads and writes inside a granted Storage Access Framework tree. Locations
 * are resolved one exact, visible name at a time from the tree's own root, so a
 * request can only reach documents inside the tree the user granted.
 */
internal class SharedFolderStorage(private val resolver: ContentResolver) {
    data class Node(
        val documentId: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long?,
        val modified: Long?,
        val canCreate: Boolean,
    )

    fun hasGrant(tree: Uri): Boolean = resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }

    fun canWrite(tree: Uri): Boolean = resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    fun documentUri(tree: Uri, documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)

    /** The node at [path] (`""` is the tree itself), or null when a name does not resolve. */
    fun resolve(tree: Uri, path: String): Node? = guarded {
        var node = root(tree)
        if (path.isEmpty()) return@guarded node
        for (name in path.split('/')) {
            if (!node.isDirectory || !BrowseRules.isVisible(name)) return@guarded null
            node = children(tree, node.documentId).firstOrNull { it.name == name } ?: return@guarded null
        }
        node
    }

    /** The visible children of a folder, in provider order. */
    fun children(tree: Uri, documentId: String): List<Node> = guarded {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val cursor = resolver.query(uri, PROJECTION, null, null, null) ?: throw SharedFolderUnavailableException()
        cursor.use {
            val nodes = ArrayList<Node>(it.count.coerceAtLeast(0))
            while (it.moveToNext()) node(it)?.takeIf { node -> BrowseRules.isVisible(node.name) }?.let(nodes::add)
            nodes
        }
    }

    /** Creates an empty file named exactly [name] (no MIME-based extension) and returns its document URI. */
    fun createFile(tree: Uri, directoryId: String, name: String): Uri = guarded {
        DocumentsContract.createDocument(resolver, documentUri(tree, directoryId), "application/octet-stream", name)
            ?: throw java.io.IOException("Could not create document")
    }

    private fun root(tree: Uri): Node {
        val uri = documentUri(tree, DocumentsContract.getTreeDocumentId(tree))
        val cursor = resolver.query(uri, PROJECTION, null, null, null) ?: throw SharedFolderUnavailableException()
        return cursor.use { if (it.moveToFirst()) node(it) else null } ?: throw SharedFolderUnavailableException()
    }

    private fun node(cursor: android.database.Cursor): Node? {
        val id = cursor.getString(0) ?: return null
        val name = cursor.getString(1) ?: return null
        val flags = if (cursor.isNull(5)) 0 else cursor.getInt(5)
        return Node(
            documentId = id,
            name = name,
            isDirectory = cursor.getString(2) == Document.MIME_TYPE_DIR,
            size = if (cursor.isNull(3)) null else cursor.getLong(3).takeIf { it >= 0 },
            modified = if (cursor.isNull(4)) null else cursor.getLong(4).takeIf { it > 0 },
            canCreate = flags and Document.FLAG_DIR_SUPPORTS_CREATE != 0,
        )
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (error: SharedFolderUnavailableException) {
        throw error
    } catch (error: SecurityException) {
        throw SharedFolderUnavailableException(error)
    } catch (error: java.io.FileNotFoundException) {
        throw SharedFolderUnavailableException(error)
    } catch (error: IllegalArgumentException) {
        // Providers throw this for documents that no longer exist.
        throw SharedFolderUnavailableException(error)
    }

    private companion object {
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}

/**
 * Shares a folder picked with `ACTION_OPEN_DOCUMENT_TREE`: keeps the grant across
 * restarts (read and write when offered, otherwise read only) and remembers the
 * folder under a random ID. Returns false when nothing was added.
 */
internal fun BridgeySettings.shareFolder(resolver: ContentResolver, tree: Uri): Boolean {
    val readWrite = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    val granted = runCatching { resolver.takePersistableUriPermission(tree, readWrite) }.isSuccess ||
        runCatching { resolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
    if (!granted) return false
    val name = runCatching {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: "Folder"
    return addSharedFolder(tree.toString(), name) != null
}

/** Stops sharing a folder and gives its grant back to the system. */
internal fun BridgeySettings.unshareFolder(resolver: ContentResolver, folder: SharedFolder) {
    removeSharedFolder(folder.id)
    val tree = Uri.parse(folder.uri)
    resolver.persistedUriPermissions.filter { it.uri == tree }.forEach { permission ->
        val flags = (if (permission.isReadPermission) android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (permission.isWritePermission) android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        runCatching { resolver.releasePersistableUriPermission(tree, flags) }
    }
}
