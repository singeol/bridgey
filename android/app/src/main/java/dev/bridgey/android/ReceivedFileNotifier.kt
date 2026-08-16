package dev.bridgey.android

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast

object ReceivedFileNotifier {
    private const val CHANNEL_ID = "bridgey_received_files_v1"

    fun show(context: Context, name: String, mimeType: String, uri: Uri) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Received files", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Files received from paired Bridgey devices"
            },
        )
        val intent = Intent()
            .setComponent(ComponentName(context, OpenReceivedFileActivity::class.java)).apply {
            data = uri
            putExtra(OpenReceivedFileActivity.EXTRA_MIME_TYPE, mimeType)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            uri.toString().hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            uri.toString().hashCode(),
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(dev.bridgey.android.R.drawable.ic_bridgey_notification)
                .setContentTitle("File received")
                .setContentText(name)
                .setSubText("Download/Bridgey")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .build(),
        )
    }
}

class OpenReceivedFileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fileUri = intent?.data
        val mimeType = intent?.getStringExtra(EXTRA_MIME_TYPE) ?: "application/octet-stream"
        val folderUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Download/Bridgey",
        )
        val folderIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addCategory(Intent.CATEGORY_OPENABLE)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val openedFolder = folderIntent.resolveActivity(packageManager) != null &&
            runCatching { startActivity(folderIntent) }.isSuccess
        if (!openedFolder && fileUri != null) {
            val fileIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { startActivity(fileIntent) }.isFailure) {
                Toast.makeText(this, "File saved to Download/Bridgey", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_MIME_TYPE = "dev.bridgey.android.extra.MIME_TYPE"
    }
}
