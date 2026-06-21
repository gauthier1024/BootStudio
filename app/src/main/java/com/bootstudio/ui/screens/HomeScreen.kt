package com.bootstudio.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import utils.BootAnimParser
import java.io.File

data class BootAnimation(
    val name: String,
    val assetPath: String,
    val preview: Bitmap? = null,
    val videoUri: Uri? = null
)

@Composable
fun HomeScreen(onPreview: (String) -> Unit = {}) {
    val context = LocalContext.current
    var animations by remember { mutableStateOf<List<BootAnimation>>(emptyList()) }
    var playingAnim by remember { mutableStateOf<BootAnimation?>(null) }

    LaunchedEffect(Unit) {
        val assetList = context.assets.list("bootanimations") ?: emptyArray()
        val zipFiles = assetList.filter { it.startsWith("bootanimation_") && it.endsWith(".zip") }
        
        // Step 1: Immediately show all rectangles with titles
        val initialAnims = zipFiles.map { fileName ->
            val name = fileName.removePrefix("bootanimation_").removeSuffix(".zip")
            val fullPath = "bootanimations/$fileName"
            BootAnimation(
                name = name,
                assetPath = fullPath,
                preview = null,
                videoUri = null
            )
        }
        animations = initialAnims

        // Step 2: Load previews in background and update cards one by one
        withContext(Dispatchers.IO) {
            zipFiles.forEachIndexed { index, fileName ->
                val fullPath = "bootanimations/$fileName"
                val preview = BootAnimParser.getFirstFrame(context, fullPath)
                
                withContext(Dispatchers.Main) {
                    animations = animations.toMutableList().also { list ->
                        list[index] = list[index].copy(preview = preview)
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "My Library",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (animations.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No animations found in assets/bootanimations/", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(animations) { anim ->
                        AnimationCard(anim, onPlay = { 
                            // Copy to cache for preview if it's in assets
                            val file = File(context.cacheDir, anim.assetPath.split("/").last())
                            if (!file.exists()) {
                                context.assets.open(anim.assetPath).use { input ->
                                    file.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                            onPreview(file.absolutePath)
                        })
                    }
                }
            }
        }

        if (playingAnim != null) {
            BootAnimPlayer(playingAnim!!, onDismiss = { playingAnim = null })
        }
    }
}

@Composable
fun AnimationCard(animation: BootAnimation, onPlay: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clickable { onPlay() },
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left Side: Name and Info
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = animation.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Boot Animation",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Right Side: Video Preview
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                    .background(Color.Black)
            ) {
                if (animation.videoUri != null) {
                    VideoPreview(animation.videoUri)
                } else if (animation.preview != null) {
                    Image(
                        bitmap = animation.preview.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                
                // Play Icon Overlay
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.Center).size(32.dp)
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPreview(uri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
            prepare()
            volume = 0f // Mute preview
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = {
            PlayerView(context).apply {
                player = exoPlayer
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun BootAnimPlayer(animation: BootAnimation, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var currentFrame by remember { mutableStateOf<Bitmap?>(null) }
    var isLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(animation) {
        val desc = BootAnimParser.parseDescFromAssets(context, animation.assetPath) ?: return@LaunchedEffect
        val frameDuration = 1000L / desc.fps
        
        isLoaded = true
        for (part in desc.parts) {
            val frames = BootAnimParser.getFramesForPartFromAssets(context, animation.assetPath, part.folder)
            if (frames.isEmpty()) continue

            val loopCount = if (part.loop == 0) 5 else part.loop
            repeat(loopCount) {
                for (frame in frames) {
                    currentFrame = frame
                    delay(frameDuration)
                }
                delay(part.pause * frameDuration)
            }
        }
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            if (currentFrame != null) {
                Image(
                    bitmap = currentFrame!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else if (!isLoaded) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}
