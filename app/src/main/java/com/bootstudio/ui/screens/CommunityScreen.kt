package com.bootstudio.ui.screens

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import com.bootstudio.service.DownloadService
import com.bootstudio.ui.components.VideoPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
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
    var animations by remember { mutableStateOf<List<CommunityAnimation>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showInfoDialog by remember { mutableStateOf(false) }
    
    // Collect downloading items from the service
    val downloadingItems by DownloadService.downloadingItems.collectAsState()

    // Use raw.githubusercontent.com for direct access to files
    val jsonUrl = "https://raw.githubusercontent.com/gauthier1024/BootStudio/main/BootAnimations/bootanimations.json"
    val baseUrl = "https://raw.githubusercontent.com/gauthier1024/BootStudio/main/BootAnimations/"

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val response = URL(jsonUrl).readText()
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
                            previewUrl = "${baseUrl}${title}/preview.mp4"
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    animations = list
                    isLoading = false
                }
            } catch (e: Exception) {
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
                    contentPadding = PaddingValues(bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(animations) { anim ->
                        CommunityAnimationCard(
                            animation = anim,
                            downloadProgress = downloadingItems[anim.title],
                            onDownload = {
                                val intent = Intent(context, DownloadService::class.java).apply {
                                    action = DownloadService.ACTION_START_DOWNLOAD
                                    putExtra(DownloadService.EXTRA_ANIM_TITLE, anim.title)
                                    putExtra(DownloadService.EXTRA_ANIM_CREATOR, anim.creator)
                                    putExtra(DownloadService.EXTRA_DOWNLOAD_URL, anim.downloadUrl)
                                    putExtra(DownloadService.EXTRA_PREVIEW_URL, anim.previewUrl)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
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
    downloadProgress: Int?,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    val isDownloading = downloadProgress != null
    val isDownloaded = remember(animation.title, isDownloading) {
        File(context.filesDir, "library/${animation.title}.zip").exists()
    }

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
            // Preview MP4
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
            ) {
                VideoPreview(uri = Uri.parse(animation.previewUrl))
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
                
                if (isDownloading) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = (downloadProgress ?: 0) / 100f,
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
            }

            if (isDownloading) {
                Box(modifier = Modifier.padding(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                IconButton(
                    onClick = onDownload,
                    enabled = !isDownloaded
                ) {
                    Icon(
                        imageVector = if (isDownloaded) Icons.Default.Check else Icons.Default.Add,
                        contentDescription = if (isDownloaded) "Downloaded" else "Download",
                        tint = if (isDownloaded) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
