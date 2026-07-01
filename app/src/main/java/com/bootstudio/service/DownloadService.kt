package com.bootstudio.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bootstudio.ui.screens.CommunityAnimation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeDownloads = mutableMapOf<String, Job>()
    
    companion object {
        private val _downloadingItems = MutableStateFlow<Map<String, Int>>(emptyMap())
        val downloadingItems = _downloadingItems.asStateFlow()

        const val ACTION_START_DOWNLOAD = "com.bootstudio.ACTION_START_DOWNLOAD"
        const val EXTRA_ANIM_TITLE = "extra_anim_title"
        const val EXTRA_ANIM_CREATOR = "extra_anim_creator"
        const val EXTRA_DOWNLOAD_URL = "extra_download_url"
        const val EXTRA_PREVIEW_URL = "extra_preview_url"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_DOWNLOAD) {
            val title = intent.getStringExtra(EXTRA_ANIM_TITLE) ?: return START_NOT_STICKY
            val creator = intent.getStringExtra(EXTRA_ANIM_CREATOR) ?: ""
            val downloadUrl = intent.getStringExtra(EXTRA_DOWNLOAD_URL) ?: ""
            val previewUrl = intent.getStringExtra(EXTRA_PREVIEW_URL) ?: ""

            val anim = CommunityAnimation(title, creator, downloadUrl, previewUrl)
            startDownload(anim)
        }
        return START_NOT_STICKY
    }

    private fun startDownload(anim: CommunityAnimation) {
        if (activeDownloads.containsKey(anim.title)) return

        val notificationId = anim.title.hashCode()
        createNotificationChannel()

        val job = serviceScope.launch {
            _downloadingItems.update { it + (anim.title to 0) }
            
            val notificationBuilder = NotificationCompat.Builder(this@DownloadService, "community_download_channel")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading ${anim.title}")
                .setContentText("Connecting...")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setProgress(100, 0, true)

            startForeground(notificationId, notificationBuilder.build())

            var success = false
            val libraryDir = File(filesDir, "library")
            if (!libraryDir.exists()) libraryDir.mkdirs()
            val targetFile = File(libraryDir, "${anim.title}.zip")

            val previewDir = File(filesDir, "previews")
            if (!previewDir.exists()) previewDir.mkdirs()
            val targetPreviewFile = File(previewDir, "${targetFile.name}.mp4")

            try {
                // Download Zip
                val url = URL(anim.downloadUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val fileLength = connection.contentLength
                    connection.inputStream.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(4096)
                            var total: Long = 0
                            var count: Int
                            var lastProgressUpdate = 0
                            while (input.read(buffer).also { count = it } != -1) {
                                ensureActive()
                                total += count
                                output.write(buffer, 0, count)
                                if (fileLength > 0) {
                                    val progress = ((total * 100) / fileLength).toInt()
                                    if (progress > lastProgressUpdate) {
                                        lastProgressUpdate = progress
                                        _downloadingItems.update { it + (anim.title to progress) }
                                        notificationBuilder.setProgress(100, progress, false)
                                            .setContentText("$progress%")
                                        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                        manager.notify(notificationId, notificationBuilder.build())
                                    }
                                }
                            }
                        }
                    }

                    // Download Preview MP4
                    try {
                        val previewUrl = URL(anim.previewUrl)
                        val previewConnection = previewUrl.openConnection() as HttpURLConnection
                        previewConnection.connect()
                        if (previewConnection.responseCode == HttpURLConnection.HTTP_OK) {
                            previewConnection.inputStream.use { input ->
                                FileOutputStream(targetPreviewFile).use { output ->
                                    val buffer = ByteArray(4096)
                                    var bytesRead: Int
                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        ensureActive()
                                        output.write(buffer, 0, bytesRead)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        e.printStackTrace()
                    }

                    ensureActive()

                    // Save metadata
                    getSharedPreferences("anim_metadata", Context.MODE_PRIVATE)
                        .edit()
                        .putString("${targetFile.name}_tag", "Community")
                        .putString("${targetFile.name}_creator", anim.creator)
                        .apply()

                    success = true
                    
                    val finalNotification = NotificationCompat.Builder(this@DownloadService, "community_download_channel")
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("Download complete")
                        .setContentText("Successfully downloaded ${anim.title}")
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .build()
                    
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(notificationId, finalNotification)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (e !is CancellationException) {
                    val failNotification = NotificationCompat.Builder(this@DownloadService, "community_download_channel")
                        .setSmallIcon(android.R.drawable.stat_sys_warning)
                        .setContentTitle("Download failed")
                        .setContentText("${anim.title}: ${e.message}")
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .build()
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(notificationId, failNotification)
                }
            } finally {
                if (!success) {
                    if (targetFile.exists()) targetFile.delete()
                    if (targetPreviewFile.exists()) targetPreviewFile.delete()
                }
                _downloadingItems.update { it - anim.title }
                activeDownloads.remove(anim.title)
                if (activeDownloads.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }
        activeDownloads[anim.title] = job
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "community_download_channel",
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
