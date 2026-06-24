package com.bootstudio.ui.screens

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import utils.DiagnosticLogger
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class CommunityAnimation(
    val title: String,
    val creator: String,
    val downloadUrl: String,
    val previewUrl: String
)

@Composable
fun CommunityScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var animations by remember { mutableStateOf<List<CommunityAnimation>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showInfoDialog by remember { mutableStateOf(false) }
    val downloadingItems = remember { mutableStateListOf<String>() }

    // Use raw.githubusercontent.com for direct access to files
    val jsonUrl = "https://raw.githubusercontent.com/gauthier1024/BootStudio/Creating_the_app/BootAnimations/bootanimations.json"
    val baseUrl = "https://raw.githubusercontent.com/gauthier1024/BootStudio/Creating_the_app/BootAnimations/"

    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }

    LaunchedEffect(Unit) {
        DiagnosticLogger.log("CommunityScreen: Fetching animations from $jsonUrl")
        withContext(Dispatchers.IO) {
            try {
                val response = URL(jsonUrl).readText()
                DiagnosticLogger.log("CommunityScreen: Received JSON response")
                // Sanitize JSON (remove trailing commas if any)
                val sanitizedResponse = response.replace(",\\s*([}\\]])".toRegex(), "$1")
                val jsonObject = JSONObject(sanitizedResponse)
                val jsonArray = jsonObject.getJSONArray("bootanimations")
                val list = mutableListOf<CommunityAnimation>()
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val title = item.getString("title")
                    val creator = item.getString("creator")
                    list.add(
                        CommunityAnimation(
                            title = title,
                            creator = creator,
                            downloadUrl = "${baseUrl}${title}/bootanimation.zip",
                            previewUrl = "${baseUrl}${title}/preview.gif"
                        )
                    )
                }
                DiagnosticLogger.log("CommunityScreen: Parsed ${list.size} animations")
                withContext(Dispatchers.Main) {
                    animations = list
                    isLoading = false
                }
            } catch (e: Exception) {
                DiagnosticLogger.log("CommunityScreen: Error fetching animations: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isLoading = false
                    Toast.makeText(context, "Failed to load community animations", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text(
                    text = "Community Store",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = {
                    showInfoDialog = true
                }) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "About Community Store",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (showInfoDialog) {
                AlertDialog(
                    onDismissRequest = { showInfoDialog = false },
                    title = { Text("How to contribute") },
                    text = { 
                        Text("Want to see your animations here? You can contribute by opening a Pull Request on GitHub. For detailed instructions, check the BootStudio README.") 
                    },
                    confirmButton = {
                        TextButton(onClick = { showInfoDialog = false }) {
                            Text("OK")
                        }
                    }
                )
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (animations.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No animations found", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(animations) { anim ->
                        CommunityAnimationCard(
                            animation = anim,
                            isDownloading = downloadingItems.contains(anim.title),
                            imageLoader = imageLoader,
                            onDownload = {
                                downloadingItems.add(anim.title)
                                scope.launch {
                                    downloadAnimation(context, anim) {
                                        downloadingItems.remove(anim.title)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CommunityAnimationCard(
    animation: CommunityAnimation,
    isDownloading: Boolean,
    imageLoader: ImageLoader,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(120.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Preview GIF
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(animation.previewUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    imageLoader = imageLoader,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = animation.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "by ${animation.creator}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onDownload) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private suspend fun downloadAnimation(
    context: Context,
    anim: CommunityAnimation,
    onComplete: () -> Unit
) {
    withContext(Dispatchers.IO) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "community_download_channel"
        val notificationId = anim.title.hashCode()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${anim.title}")
            .setContentText("Connecting...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(100, 0, true)

        notificationManager.notify(notificationId, notificationBuilder.build())

        try {
            DiagnosticLogger.log("CommunityScreen: Starting download for ${anim.title}")
            val libraryDir = File(context.filesDir, "library")
            if (!libraryDir.exists()) libraryDir.mkdirs()
            val targetFile = File(libraryDir, "${anim.title}.zip")

            val previewDir = File(context.filesDir, "previews")
            if (!previewDir.exists()) previewDir.mkdirs()
            val targetPreviewFile = File(previewDir, "${targetFile.name}_v2.gif")

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
                            total += count
                            output.write(buffer, 0, count)
                            if (fileLength > 0) {
                                val progress = ((total * 100) / fileLength).toInt()
                                if (progress > lastProgressUpdate) {
                                    lastProgressUpdate = progress
                                    notificationBuilder.setProgress(100, progress, false)
                                        .setContentText("$progress%")
                                    notificationManager.notify(notificationId, notificationBuilder.build())
                                }
                            }
                        }
                    }
                }
                DiagnosticLogger.log("CommunityScreen: Animation ZIP downloaded for ${anim.title}")

                // Download Preview GIF
                try {
                    val previewUrl = URL(anim.previewUrl)
                    val previewConnection = previewUrl.openConnection() as HttpURLConnection
                    previewConnection.connect()
                    if (previewConnection.responseCode == HttpURLConnection.HTTP_OK) {
                        previewConnection.inputStream.use { input ->
                            FileOutputStream(targetPreviewFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        DiagnosticLogger.log("CommunityScreen: Preview GIF downloaded for ${anim.title}")
                    }
                } catch (e: Exception) {
                    DiagnosticLogger.log("CommunityScreen: Preview GIF download failed for ${anim.title}: ${e.message}")
                    e.printStackTrace() // Non-critical failure
                }

                // Save metadata
                context.getSharedPreferences("anim_metadata", Context.MODE_PRIVATE)
                    .edit()
                    .putString("${targetFile.name}_tag", "Community")
                    .putString("${targetFile.name}_creator", anim.creator)
                    .apply()

                notificationBuilder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentText("Download complete")
                    .setOngoing(false)
                    .setProgress(0, 0, false)
                notificationManager.notify(notificationId, notificationBuilder.build())
                DiagnosticLogger.log("CommunityScreen: Download success for ${anim.title}")

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Successfully downloaded ${anim.title}", Toast.LENGTH_SHORT).show()
                }
            } else {
                DiagnosticLogger.log("CommunityScreen: Download failed for ${anim.title}: HTTP ${connection.responseCode}")
                notificationBuilder.setContentText("Failed to download: HTTP ${connection.responseCode}")
                    .setOngoing(false)
                    .setProgress(0, 0, false)
                notificationManager.notify(notificationId, notificationBuilder.build())

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to download: HTTP ${connection.responseCode}", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            DiagnosticLogger.log("CommunityScreen: Download exception for ${anim.title}: ${e.message}")
            e.printStackTrace()
            notificationBuilder.setContentText("Download failed: ${e.message}")
                .setOngoing(false)
                .setProgress(0, 0, false)
            notificationManager.notify(notificationId, notificationBuilder.build())

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } finally {
            withContext(Dispatchers.Main) { onComplete() }
        }
    }
}
