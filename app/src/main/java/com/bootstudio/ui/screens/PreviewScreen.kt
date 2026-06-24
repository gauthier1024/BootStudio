package com.bootstudio.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import utils.BootAnimDesc
import utils.BootAnimParser
import utils.BootAnimPart
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(zipPath: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zipFile = remember { File(zipPath) }
    
    val audioPlayer = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(Unit) {
        onDispose { audioPlayer.release() }
    }

    // Get actual device metrics for a true representation
    val dm = context.resources.displayMetrics
    val deviceWidthPx = dm.widthPixels.toFloat()
    val deviceHeightPx = dm.heightPixels.toFloat()
    val deviceAspectRatio = deviceWidthPx / deviceHeightPx

    var desc by remember { mutableStateOf<BootAnimDesc?>(null) }
    var currentFrame by remember { mutableStateOf<Bitmap?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var isPreparing by remember { mutableStateOf(false) }
    var currentPartIndex by remember { mutableStateOf(-1) }
    var statusMessage by remember { mutableStateOf("Initializing...") }

    BackHandler {
        onBack()
    }

    LaunchedEffect(zipFile) {
        desc = BootAnimParser.parseDesc(zipFile)
        if (desc == null) {
            statusMessage = "Error: Could not parse desc.txt"
        } else {
            statusMessage = "Ready to play"
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                title = { Text("Preview", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Removed play button from here as it's now in the center
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Visual Preview with Phone Frame matching ACTUAL device ratio
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.2f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                // Phone Frame matching the user's actual device proportions
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(deviceAspectRatio)
                        .padding(8.dp),
                    shape = RoundedCornerShape(32.dp),
                    color = Color.Black,
                    border = androidx.compose.foundation.BorderStroke(4.dp, Color(0xFF1A1A1A)),
                    shadowElevation = 16.dp
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (currentFrame != null && desc != null) {
                            // Represent the animation at its actual scale relative to the device resolution
                            // If the animation is larger than the screen, it will be cropped
                            BoxWithConstraints(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                val animWidth = maxWidth * (desc!!.width.toFloat() / deviceWidthPx)
                                val animHeight = maxHeight * (desc!!.height.toFloat() / deviceHeightPx)

                                Image(
                                    bitmap = currentFrame!!.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.requiredSize(animWidth, animHeight),
                                    contentScale = ContentScale.FillBounds
                                )
                            }
                        }

                        if (!isPlaying && !isPreparing && desc != null) {
                            IconButton(
                                onClick = {
                                    isPreparing = true
                                    scope.launch {
                                        playAnimation(
                                            context = context,
                                            zipFile = zipFile,
                                            desc = desc!!,
                                            audioPlayer = audioPlayer,
                                            onFrameUpdate = { currentFrame = it },
                                            onPartUpdate = { currentPartIndex = it },
                                            onFinished = {
                                                isPlaying = false
                                                isPreparing = false
                                                currentPartIndex = -1
                                                currentFrame = null
                                            },
                                            onStarted = {
                                                isPlaying = true
                                                isPreparing = false
                                            }
                                        )
                                    }
                                },
                                modifier = Modifier.size(80.dp)
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    modifier = Modifier.size(64.dp),
                                    tint = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        }
else if (desc == null || isPreparing) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f))
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    if (isPreparing) "Preparing..." else "Loading...",
                                    color = Color.Gray,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }

            // Fancy Tree Area
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Sequence Tree",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        if (desc != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (desc!!.isStandard == false) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.padding(end = 8.dp)
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
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "${desc!!.width}x${desc!!.height} @ ${desc!!.fps}fps",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    if (desc != null) {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            items(desc!!.parts.size) { index ->
                                val part = desc!!.parts[index]
                                // Check if audio exists for this part
                                var hasAudio by remember { mutableStateOf(false) }
                                LaunchedEffect(part) {
                                    withContext(Dispatchers.IO) {
                                        hasAudio = BootAnimParser.hasAudioForPart(zipFile, part.folder)
                                    }
                                }
                                FancyStepItem(
                                    part = part,
                                    isActive = isPlaying && currentPartIndex == index,
                                    isLast = index == desc!!.parts.size - 1,
                                    hasAudio = hasAudio
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FancyStepItem(part: BootAnimPart, isActive: Boolean, isLast: Boolean, hasAudio: Boolean = false) {
    val activeColor = if (part.type == 'c') Color(0xFF4CAF50) else Color(0xFF2196F3)
    val color = if (isActive) activeColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        // Timeline column
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(24.dp)) {
            Box(
                modifier = Modifier
                    .size(if (isActive) 12.dp else 8.dp)
                    .background(color, shape = CircleShape)
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                )
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // Content column
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                text = "Folder: ${part.folder}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                InfoTag(
                    text = if (part.type == 'c') "COMPLETE" else "PARTIAL",
                    color = activeColor.copy(alpha = if (isActive) 1f else 0.4f)
                )
                InfoTag(
                    text = "Loop: ${if (part.loop == 0) "∞" else part.loop}",
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = if (isActive) 1f else 0.4f)
                )
                if (part.pause > 0) {
                    InfoTag(
                        text = "Pause: ${part.pause}f",
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = if (isActive) 1f else 0.4f)
                    )
                }
                if (hasAudio) {
                    InfoTag(
                        text = "AUDIO",
                        color = Color(0xFFFF9800).copy(alpha = if (isActive) 1f else 0.4f)
                    )
                }
            }
        }
    }
}

@Composable
fun InfoTag(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.2f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

private suspend fun playAnimation(
    context: android.content.Context,
    zipFile: File,
    desc: BootAnimDesc,
    audioPlayer: ExoPlayer,
    onFrameUpdate: (Bitmap) -> Unit,
    onPartUpdate: (Int) -> Unit,
    onFinished: () -> Unit,
    onStarted: () -> Unit
) {
    val frameDuration = 1000L / desc.fps
    val tempDir = File(context.cacheDir, "preview_run_${System.currentTimeMillis()}")
    tempDir.mkdirs()

    try {
        // 1. Extract all frames and audio info in one pass
        val folderToPartMap = desc.parts.mapIndexed { index, part -> 
            part.folder.lowercase().trimEnd('/') to index 
        }.toMap()
        
        val framesByPart = Array(desc.parts.size) { mutableListOf<File>() }
        val audioByPart = arrayOfNulls<File>(desc.parts.size)

        withContext(Dispatchers.IO) {
            java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val nameLower = entry.name.lowercase()
                    
                    // Check for audio
                    val audioFolder = folderToPartMap.keys.find { 
                        nameLower == "$it/audio.wav" || nameLower == "$it\\audio.wav" 
                    }
                    if (audioFolder != null) {
                        val partIndex = folderToPartMap[audioFolder]!!
                        val audioFile = File(tempDir, "audio_$partIndex.wav")
                        FileOutputStream(audioFile).use { zip.copyTo(it) }
                        audioByPart[partIndex] = audioFile
                    } 
                    // Check for frames
                    else if (!entry.isDirectory && (nameLower.endsWith(".png") || nameLower.endsWith(".jpg") || 
                        nameLower.endsWith(".jpeg") || nameLower.endsWith(".webp"))) {
                        
                        val frameFolder = folderToPartMap.keys.find { 
                            nameLower.startsWith("$it/") || nameLower.startsWith("$it\\") 
                        }
                        if (frameFolder != null) {
                            val partIndex = folderToPartMap[frameFolder]!!
                            val frameFile = File(tempDir, "part${partIndex}_${entry.name.replace("/", "_").replace("\\", "_")}")
                            FileOutputStream(frameFile).use { zip.copyTo(it) }
                            framesByPart[partIndex].add(frameFile)
                        }
                    }
                    entry = zip.nextEntry
                }
            }
            // Sort frames for each part
            framesByPart.forEach { it.sortBy { file -> file.name } }
        }

        onStarted()

        desc.parts.forEachIndexed { index, part ->
            onPartUpdate(index)
            val frames = framesByPart[index]
            val audioFile = audioByPart[index]

            if (frames.isNotEmpty()) {
                val loopCount = if (part.loop == 0) 3 else part.loop
                repeat(loopCount) {
                    // Play audio if it exists
                    if (audioFile != null) {
                        withContext(Dispatchers.Main) {
                            audioPlayer.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(audioFile)))
                            audioPlayer.prepare()
                            audioPlayer.play()
                        }
                    }

                    for (frameFile in frames) {
                        val bitmap = withContext(Dispatchers.IO) {
                            BitmapFactory.decodeFile(frameFile.absolutePath)
                        }
                        if (bitmap != null) {
                            onFrameUpdate(bitmap)
                        }
                        delay(frameDuration)
                    }
                    repeat(part.pause) {
                        delay(frameDuration)
                    }

                    withContext(Dispatchers.Main) {
                        audioPlayer.stop()
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        tempDir.deleteRecursively()
        onFinished()
    }
}
