package com.bootstudio.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import utils.BootAnimParser
import utils.CommandExecutor
import utils.FFmpegDownloader
import utils.MagiskManager
import java.io.File

data class BootAnimation(
    val name: String,
    val path: String,
    val isAsset: Boolean,
    val tag: String? = null,
    val preview: Bitmap? = null,
    val videoUri: Uri? = null,
    val resolution: String? = null,
    val isNonStandard: Boolean = false
)

@Composable
fun HomeScreen(onPreview: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var animations by remember { mutableStateOf<List<BootAnimation>>(emptyList()) }
    var playingAnim by remember { mutableStateOf<BootAnimation?>(null) }
    var systemAnimToUse by remember { mutableStateOf<String?>(null) }
    var savedSystemPath by remember { mutableStateOf<String?>(null) }
    var appliedPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            CommandExecutor.initRootSession()
        }

        val prefs = context.getSharedPreferences("bootstudio_prefs", android.content.Context.MODE_PRIVATE)
        val systemPath = prefs.getString("boot_anim_path", null)
        savedSystemPath = systemPath
        
        // Load the previously saved applied path
        appliedPath = prefs.getString("applied_anim_path", "system_default")
        
        val assetList = context.assets.list("bootanimations") ?: emptyArray()
        val zipFiles = assetList.filter { it.startsWith("bootanimation_") && it.endsWith(".zip") }
        
        val initialAnims = mutableListOf<BootAnimation>()
        
        // Use root to copy the file to a location the app can read if it's in a restricted directory
        val prepareSystemAnim: suspend (String) -> String? = { sourcePath ->
            withContext(Dispatchers.IO) {
                val target = File(context.cacheDir, "system_backup.zip")
                CommandExecutor.executeWithSu("cp \"$sourcePath\" \"${target.absolutePath}\" && chmod 644 \"${target.absolutePath}\"")
                if (target.exists()) target.absolutePath else null
            }
        }

        var localSystemPath: String? = null
        val moduleRoot = "/data/adb/modules/BootStudio"
        
        if (systemPath != null) {
            val backupFileName = systemPath.trimStart('/').replace('/', '_')
            val backupPath = "$moduleRoot/original/$backupFileName"

            // Use detected path from preferences
            val backupExists = withContext(Dispatchers.IO) {
                CommandExecutor.executeWithSu("[ -f \"$backupPath\" ] && echo \"exists\"").contains("exists")
            }
            if (backupExists) {
                localSystemPath = prepareSystemAnim(backupPath)
            } else {
                // Fallback to direct system path if backup doesn't exist yet
                val detectedExists = withContext(Dispatchers.IO) {
                    CommandExecutor.executeWithSu("[ -f \"$systemPath\" ] && echo \"exists\"").contains("exists")
                }
                if (detectedExists) {
                    localSystemPath = prepareSystemAnim(systemPath)
                }
            }
        }
        
        systemAnimToUse = localSystemPath

        if (localSystemPath != null && systemPath != null) {
            initialAnims.add(
                BootAnimation(
                    name = "System Animation",
                    path = systemPath, // Display the actual system path
                    isAsset = false,
                    tag = "System",
                    preview = null,
                    videoUri = null
                )
            )
        }

        // Add Asset animations
        zipFiles.forEach { fileName ->
            val name = fileName.removePrefix("bootanimation_").removeSuffix(".zip")
            val fullPath = "bootanimations/$fileName"
            initialAnims.add(
                BootAnimation(
                    name = name,
                    path = fullPath,
                    isAsset = true,
                    tag = "Built-in",
                    preview = null,
                    videoUri = null
                )
            )
        }
        
        animations = initialAnims

        // Step 2: Load previews and generate videos in background
        withContext(Dispatchers.IO) {
            animations.forEachIndexed { index, anim ->
                // For System animation, we must use the cached path for processing
                val processingPath = if (anim.tag == "System") {
                    systemAnimToUse ?: anim.path
                } else {
                    anim.path
                }

                // Load static thumbnail and metadata
                val desc = if (anim.isAsset) {
                    BootAnimParser.parseDescFromAssets(context, anim.path)
                } else {
                    BootAnimParser.parseDesc(context, File(processingPath))
                }

                val preview = if (anim.isAsset) {
                    BootAnimParser.getFirstFrame(context, anim.path)
                } else {
                    BootAnimParser.getFirstFrameFromFile(context, File(processingPath))
                }
                
                withContext(Dispatchers.Main) {
                    animations = animations.toMutableList().also { list ->
                        list[index] = list[index].copy(
                            preview = preview,
                            resolution = desc?.let { "${it.width}x${it.height}" },
                            isNonStandard = desc?.isStandard == false
                        )
                    }
                }

                // Generate video preview for System animation if FFmpeg is ready
                if (anim.tag == "System" && FFmpegDownloader.isInstalled(context)) {
                    val videoFile = File(context.cacheDir, "system_preview.mp4")
                    if (!videoFile.exists()) {
                        BootAnimParser.generateVideoPreviewFromFile(context, File(processingPath), videoFile) { success ->
                            if (success) {
                                scope.launch {
                                    animations = animations.toMutableList().also { list ->
                                        list[index] = list[index].copy(videoUri = Uri.fromFile(videoFile))
                                    }
                                }
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            animations = animations.toMutableList().also { list ->
                                list[index] = list[index].copy(videoUri = Uri.fromFile(videoFile))
                            }
                        }
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
                        val isApplied = if (anim.tag == "System") {
                            appliedPath == "system_default"
                        } else {
                            appliedPath == anim.path
                        }

                        AnimationCard(
                            animation = anim,
                            isApplied = isApplied,
                            onPlay = {
                                // ... (no change)
                                if (anim.isAsset) {
                                    val file = File(context.cacheDir, anim.path.split("/").last())
                                    if (!file.exists()) {
                                        context.assets.open(anim.path).use { input ->
                                            file.outputStream().use { output ->
                                                input.copyTo(output)
                                            }
                                        }
                                    }
                                    onPreview(file.absolutePath)
                                } else if (anim.tag == "System") {
                                    onPreview(systemAnimToUse ?: anim.path)
                                } else {
                                    onPreview(anim.path)
                                }
                            },
                            onApply = {
                                scope.launch {
                                    val currentPrefs = context.getSharedPreferences("bootstudio_prefs", android.content.Context.MODE_PRIVATE)
                                    val currentPath = savedSystemPath
                                    if (currentPath != null) {
                                        if (anim.tag == "System") {
                                            withContext(Dispatchers.IO) {
                                                MagiskManager.setDefaultAnimation(currentPath)
                                            }
                                            appliedPath = "system_default"
                                            currentPrefs.edit().putString("applied_anim_path", "system_default").apply()
                                        } else {
                                            val sourcePath = if (anim.isAsset) {
                                                val file = File(context.cacheDir, anim.path.split("/").last())
                                                withContext(Dispatchers.IO) {
                                                    context.assets.open(anim.path).use { input ->
                                                        file.outputStream().use { output ->
                                                            input.copyTo(output)
                                                        }
                                                    }
                                                }
                                                file.absolutePath
                                            } else {
                                                anim.path
                                            }

                                            withContext(Dispatchers.IO) {
                                                MagiskManager.changeBootAnimation(sourcePath, currentPath)
                                            }
                                            appliedPath = anim.path
                                            currentPrefs.edit().putString("applied_anim_path", anim.path).apply()
                                        }
                                    }
                                }
                            }
                        )
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
fun AnimationCard(
    animation: BootAnimation,
    isApplied: Boolean,
    onPlay: () -> Unit,
    onApply: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Preview Image with Play Button
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .clickable { onPlay() }
            ) {
                if (animation.preview != null) {
                    Image(
                        bitmap = animation.preview.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.4f)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.padding(4.dp).size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 2. Title and Source
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = animation.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(when {
                            animation.tag == "System" -> animation.path
                            animation.isAsset -> "Built-in"
                            else -> "Community User"
                        })
                        animation.resolution?.let {
                            append(" • $it")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 3. Tag
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                if (animation.isNonStandard) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = "Modified",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (animation.tag != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = animation.tag,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 4. Circular Apply Button
            if (isApplied) {
                FilledIconButton(
                    onClick = onApply,
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (animation.tag == "System") 
                            MaterialTheme.colorScheme.secondaryContainer 
                        else MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Applied",
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                OutlinedIconButton(
                    onClick = onApply,
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    // Empty circle
                }
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
        val desc = if (animation.isAsset) {
            BootAnimParser.parseDescFromAssets(context, animation.path)
        } else {
            BootAnimParser.parseDesc(context, File(animation.path))
        } ?: return@LaunchedEffect

        val frameDuration = 1000L / desc.fps
        
        isLoaded = true
        for (part in desc.parts) {
            val frames = if (animation.isAsset) {
                BootAnimParser.getFramesForPartFromAssets(context, animation.path, part.folder)
            } else {
                BootAnimParser.getFramesForPart(File(animation.path), part.folder)
            }
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
