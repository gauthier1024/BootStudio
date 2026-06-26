package com.bootstudio.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
    var currentPartIndex by remember { mutableIntStateOf(-1) }
    var currentFrameIndex by remember { mutableIntStateOf(0) }
    var totalFramesInPart by remember { mutableIntStateOf(0) }
    var statusMessage by remember { mutableStateOf("Initializing...") }
    var timerSeconds by remember { mutableIntStateOf(15) }
    var isBooting by remember { mutableStateOf(false) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            timerSeconds = 15
            while (timerSeconds > 0) {
                delay(1000)
                timerSeconds--
            }
        } else {
            timerSeconds = 15
        }
    }

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
                    if (isPlaying) {
                        Text(
                            text = "Boot finished in: ${timerSeconds}s",
                            color = Color.White,
                            modifier = Modifier.padding(end = 16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
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
                            if (isBooting) {
                                Text(
                                    text = "Booting...",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                    style = MaterialTheme.typography.headlineSmall
                                )
                            } else {
                                IconButton(
                                    onClick = {
                                        isPreparing = true
                                        scope.launch {
                                            playAnimation(
                                                context = context,
                                                zipFile = zipFile,
                                                desc = desc!!,
                                                audioPlayer = audioPlayer,
                                                timerSeconds = derivedStateOf { timerSeconds },
                                                onFrameUpdate = { currentFrame = it },
                                                onPartUpdate = { index, count ->
                                                    currentPartIndex = index
                                                    totalFramesInPart = count
                                                },
                                                onFrameIndexUpdate = { currentFrameIndex = it },
                                                onFinished = {
                                                    scope.launch {
                                                        // Clear current frame and active part to show black screen while waiting
                                                        currentFrame = null
                                                        currentPartIndex = -1

                                                        // Wait for timer to finish if it hasn't already
                                                        while (timerSeconds > 0) {
                                                            delay(100)
                                                        }
                                                        isPlaying = false
                                                        isPreparing = false
                                                        isBooting = true
                                                        delay(1000)
                                                        isBooting = false
                                                    }
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
                                if (!desc!!.isStandard) {
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
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
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
                                    hasAudio = hasAudio,
                                    currentFrame = if (currentPartIndex == index) currentFrameIndex else 0,
                                    totalFrames = if (currentPartIndex == index) totalFramesInPart else 0
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
fun FancyStepItem(
    part: BootAnimPart,
    isActive: Boolean,
    hasAudio: Boolean = false,
    currentFrame: Int = 0,
    totalFrames: Int = 0
) {
    val activeColor = if (part.type == 'c') Color(0xFF4CAF50) else Color(0xFF2196F3)

    val animatedScale by animateFloatAsState(if (isActive) 1.01f else 1f, label = "card_scale")
    val animatedElevation by animateDpAsState(if (isActive) 6.dp else 0.dp, label = "card_elevation")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(animatedScale)
            .padding(horizontal = 4.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = animatedElevation),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isActive) 2.dp else 1.dp,
            color = if (isActive) activeColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) activeColor.copy(alpha = 0.03f) else MaterialTheme.colorScheme.surface
        )
    ) {
        // Content column
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = part.folder,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                    color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    letterSpacing = 0.5.sp
                )
                if (isActive && totalFrames > 0) {
                    Text(
                        text = "${currentFrame + 1}/$totalFrames",
                        style = MaterialTheme.typography.labelSmall,
                        color = activeColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            if (isActive && totalFrames > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { (currentFrame + 1).toFloat() / totalFrames },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape),
                    color = activeColor,
                    trackColor = activeColor.copy(alpha = 0.1f)
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                InfoTag(
                    text = if (part.type == 'c') "COMPLETE" else "PARTIAL",
                    color = activeColor,
                    isActive = isActive,
                    icon = if (part.type == 'c') Icons.Default.Check else null
                )
                
                InfoTag(
                    text = if (part.loop == 0) "∞" else "x${part.loop}",
                    color = MaterialTheme.colorScheme.secondary,
                    isActive = isActive,
                    icon = Icons.Default.PlayArrow
                )
                
                if (part.pause > 0) {
                    InfoTag(
                        text = "${part.pause}f",
                        color = MaterialTheme.colorScheme.tertiary,
                        isActive = isActive,
                        icon = Icons.Default.Info
                    )
                }
                
                if (hasAudio) {
                    InfoTag(
                        text = "AUDIO",
                        color = Color(0xFFFF9800),
                        isActive = isActive,
                        icon = Icons.Default.Notifications
                    )
                }
            }
        }
    }
}

@Composable
fun InfoTag(text: String, color: Color, isActive: Boolean, icon: ImageVector? = null) {
    val finalColor = if (isActive) color else color.copy(alpha = 0.5f)
    val bgColor = if (isActive) color.copy(alpha = 0.12f) else color.copy(alpha = 0.05f)

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, finalColor.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = finalColor
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = text,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                color = finalColor
            )
        }
    }
}

private suspend fun playAnimation(
    context: android.content.Context,
    zipFile: File,
    desc: BootAnimDesc,
    audioPlayer: ExoPlayer,
    timerSeconds: State<Int>,
    onFrameUpdate: (Bitmap) -> Unit,
    onPartUpdate: (Int, Int) -> Unit,
    onFrameIndexUpdate: (Int) -> Unit,
    onFinished: () -> Unit,
    onStarted: () -> Unit
) {
    val frameDuration = 1000L / desc.fps
    val tempDir = File(context.cacheDir, "preview_run_${System.currentTimeMillis()}")
    tempDir.mkdirs()

    // Optimization: Calculate inSampleSize to avoid decoding full-res images for preview
    val dm = context.resources.displayMetrics
    val targetWidth = dm.widthPixels / 2
    val targetHeight = dm.heightPixels / 2

    val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565 // Faster & less memory
        inSampleSize = 1
        if (desc.width > targetWidth || desc.height > targetHeight) {
            var sample = 1
            while (desc.width / (sample * 2) >= targetWidth && desc.height / (sample * 2) >= targetHeight) {
                sample *= 2
            }
            inSampleSize = sample
        }
    }

    try {
        // 1. Extract all frames and audio info using ZipFile for random access (faster)
        val folderToPartMap = desc.parts.mapIndexed { index, part ->
            part.folder.lowercase().trim('/', '\\') to index
        }.toMap()

        val framesByPart = Array(desc.parts.size) { mutableListOf<File>() }
        val audioByPart = arrayOfNulls<File>(desc.parts.size)

        withContext(Dispatchers.IO) {
            java.util.zip.ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue

                    val nameLower = entry.name.lowercase().replace('\\', '/')
                    val parentFolder = nameLower.substringBeforeLast('/', "").trim('/', '\\')
                    val fileName = nameLower.substringAfterLast('/')

                    if (fileName == "audio.wav") {
                        val partIndex = folderToPartMap[parentFolder]
                        if (partIndex != null) {
                            val audioFile = File(tempDir, "audio_$partIndex.wav")
                            zip.getInputStream(entry).use { it.copyTo(FileOutputStream(audioFile)) }
                            audioByPart[partIndex] = audioFile
                        }
                    } else if (fileName.endsWith(".png") || fileName.endsWith(".jpg") ||
                               fileName.endsWith(".jpeg") || fileName.endsWith(".webp")) {
                        val partIndex = folderToPartMap[parentFolder]
                        if (partIndex != null) {
                            val frameFile = File(tempDir, "p${partIndex}_$fileName")
                            zip.getInputStream(entry).use { it.copyTo(FileOutputStream(frameFile)) }
                            framesByPart[partIndex].add(frameFile)
                        }
                    }
                }
            }
            // Sort frames for each part
            framesByPart.forEach { it.sortBy { file -> file.name } }
        }

        onStarted()

        coroutineScope {
            desc.parts.forEachIndexed { index, part ->
                val frames = framesByPart[index]
                onPartUpdate(index, frames.size)
                val audioFile = audioByPart[index]

                if (frames.isNotEmpty()) {
                    val isInfinite = part.loop == 0

                    suspend fun playOnce(interruptible: Boolean): Boolean {
                        if (audioFile != null) {
                            withContext(Dispatchers.Main) {
                                audioPlayer.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(audioFile)))
                                audioPlayer.prepare()
                                audioPlayer.play()
                            }
                        }

                        // Producer-Consumer with Channel to decouple decoding from playback
                        val frameChannel = Channel<Bitmap?>(capacity = 3)
                        var isInterrupted = false

                        val decoderJob = launch(Dispatchers.IO) {
                            try {
                                for (frameFile in frames) {
                                    val bitmap = try {
                                        BitmapFactory.decodeFile(frameFile.absolutePath, options)
                                    } catch (_: Exception) { null }
                                    frameChannel.send(bitmap)
                                }
                            } finally {
                                frameChannel.close()
                            }
                        }

                        var nextFrameTime = System.currentTimeMillis()
                        repeat(frames.size) { i ->
                            if (interruptible && timerSeconds.value <= 0) {
                                isInterrupted = true
                                decoderJob.cancel()
                                return@repeat
                            }
                            
                            onFrameIndexUpdate(i)

                            val bitmap = frameChannel.receive()
                            if (bitmap != null) {
                                onFrameUpdate(bitmap)
                            }

                            nextFrameTime += frameDuration
                            val delayTime = nextFrameTime - System.currentTimeMillis()
                            if (delayTime > 0) {
                                delay(delayTime)
                            } else if (delayTime < -frameDuration * 5) {
                                nextFrameTime = System.currentTimeMillis()
                            }
                        }

                        decoderJob.join()

                        if (!isInterrupted) {
                            repeat(part.pause) {
                                if (interruptible && timerSeconds.value <= 0) return@repeat
                                delay(frameDuration)
                            }
                        }

                        if (audioFile != null) {
                            withContext(Dispatchers.Main) {
                                audioPlayer.stop()
                            }
                        }
                        return isInterrupted
                    }

                    if (!isInfinite) {
                        repeat(part.loop) {
                            playOnce(false)
                        }
                    } else {
                        if (part.type == 'c') {
                            do {
                                playOnce(false)
                            } while (timerSeconds.value > 0)
                        } else {
                            while (timerSeconds.value > 0) {
                                if (playOnce(true)) break
                            }
                        }
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
