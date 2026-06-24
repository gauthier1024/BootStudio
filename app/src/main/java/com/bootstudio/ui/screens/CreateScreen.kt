package com.bootstudio.ui.screens

import java.util.Locale
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import utils.FFmpegDownloader
import utils.ZipUtils
import java.io.File

private const val MAX_ZIP_SIZE_MB = 400

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State for inputs
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var fps by remember { mutableStateOf("") }
    var width by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }

    // Source Metadata for estimation
    var sourceWidth by remember { mutableStateOf(0) }
    var sourceHeight by remember { mutableStateOf(0) }
    var sourceFps by remember { mutableStateOf(0.0) }
    var sourceSize by remember { mutableStateOf(0L) }
    var sourceDuration by remember { mutableStateOf(0L) }
    var avgFrameSizeAtSource by remember { mutableStateOf(0L) }

    var isAdvanced by remember { mutableStateOf(false) }
    var parts by remember { mutableStateOf(listOf(AnimationPartState())) }
    var activePartIndex by remember { mutableStateOf(-1) }
    var isPickingAudio by remember { mutableStateOf(false) }

    var showSourceDialog by remember { mutableStateOf(false) }

    // Generation State
    var isGenerating by remember { mutableStateOf(false) }
    var generationStatus by remember { mutableStateOf("") }
    var generationProgress by remember { mutableStateOf(0f) }

    // FFmpeg Download State
    var showFFmpegDialog by remember { mutableStateOf(false) }
    var isDownloadingFFmpeg by remember { mutableStateOf(false) }
    var ffmpegDownloadProgress by remember { mutableStateOf(0f) }
    var ffmpegStatusMessage by remember { mutableStateOf("") }

    // Validation Dialogs
    var validationError by remember { mutableStateOf<String?>(null) }
    var validationWarning by remember { mutableStateOf<String?>(null) }

    val startAdvancedGeneration = {
        scope.launch {
            generateAdvancedBootAnimation(
                context, parts, fps, width, height,
                onProgress = { status: String, progress: Float ->
                    generationStatus = status
                    generationProgress = progress
                },
                onStart = { isGenerating = true },
                onComplete = { success: Boolean ->
                    isGenerating = false
                    if (!success) {
                        Toast.makeText(context, "Failed to create animation.", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }

    val handleFileSelection: (Uri?) -> Unit = { uri ->
        uri?.let {
            scope.launch {
                if (isPickingAudio) {
                    if (activePartIndex >= 0) {
                        parts = parts.toMutableList().also { list ->
                            list[activePartIndex] = list[activePartIndex].copy(audioUri = it)
                        }
                    }
                    isPickingAudio = false
                    return@launch
                }
                
                var selectedDuration = 0L
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, it)
                    selectedDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

                    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    val vWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val vHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0

                    val currentSourceWidth = if (rotation == 90 || rotation == 270) vHeight else vWidth
                    val currentSourceHeight = if (rotation == 90 || rotation == 270) vWidth else vHeight

                    var currentSourceFps = 30.0
                    val vFpsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    if (vFpsStr != null) {
                        currentSourceFps = vFpsStr.toDoubleOrNull() ?: 30.0
                    } else {
                        val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toDoubleOrNull()
                        if (frameCount != null && selectedDuration > 0) {
                            currentSourceFps = frameCount / (selectedDuration / 1000.0)
                        }
                    }

                    val currentSourceSize = context.contentResolver.openAssetFileDescriptor(it, "r")?.use { fd -> fd.length } ?: 0L

                    // Estimation: Extract 5 frames to get real average size
                    var estimatedAvgSize = 0L
                    withContext(Dispatchers.IO) {
                        val tempDir = File(context.cacheDir, "estimation_${System.currentTimeMillis()}")
                        tempDir.mkdirs()
                        val tempSource = File(tempDir, "source")
                        context.contentResolver.openInputStream(it)?.use { input ->
                            tempSource.outputStream().use { output -> input.copyTo(output) }
                        }

                        if (FFmpegDownloader.isInstalled(context)) {
                            FFmpegDownloader.initLoader(context)
                            val interval = selectedDuration / 5
                            var totalSize = 0L
                            var count = 0
                            for (i in 0 until 5) {
                                val ss = (i * interval) / 1000.0
                                val out = File(tempDir, "frame_$i.png")
                                // Extract one frame at timestamp ss
                                FFmpegKit.execute("-y -ss $ss -i ${tempSource.absolutePath} -vframes 1 ${out.absolutePath}")
                                if (out.exists()) {
                                    totalSize += out.length()
                                    count++
                                }
                            }
                            if (count > 0) estimatedAvgSize = totalSize / count
                        }
                        tempDir.deleteRecursively()
                    }

                    if (isAdvanced && activePartIndex >= 0) {
                        parts = parts.toMutableList().also { list ->
                            list[activePartIndex] = list[activePartIndex].copy(
                                uri = it,
                                durationMs = selectedDuration,
                                sourceWidth = currentSourceWidth,
                                sourceHeight = currentSourceHeight,
                                sourceFps = currentSourceFps,
                                sourceSize = currentSourceSize,
                                avgFrameSize = estimatedAvgSize
                            )
                        }
                    } else {
                        selectedUri = it
                        sourceWidth = currentSourceWidth
                        sourceHeight = currentSourceHeight
                        sourceFps = currentSourceFps
                        sourceSize = currentSourceSize
                        sourceDuration = selectedDuration
                        avgFrameSizeAtSource = estimatedAvgSize
                    }

                    if (!isAdvanced) fileName = it.path?.split("/")?.last() ?: "Selected File"

                    // Auto-detect metadata (only if not advanced OR if it's the first sequence)
                    if (!isAdvanced || activePartIndex == 0) {
                        if (vWidth > 0 && vHeight > 0) {
                            width = currentSourceWidth.toString()
                            height = currentSourceHeight.toString()
                        }
                        fps = currentSourceFps.toInt().toString()
                    }
                    retriever.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Launcher for selecting from Files (using OpenDocument for a real file explorer experience)
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { handleFileSelection(it) }

    // Launcher for selecting from Gallery
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { handleFileSelection(it) }

    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { handleFileSelection(it) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Create Animation",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // 1. File Upload Area (Only visible in standard mode)
        if (!isAdvanced) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clickable { showSourceDialog = true },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(24.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (selectedUri == null) "Select Video or GIF" else fileName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (selectedUri == null) {
                            Text(
                                text = "Video",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                        }


                        if (selectedUri != null) {
                            Text(
                                text = "Tap to change file",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }

        // 2. Settings Section
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Animation Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Advanced", style = MaterialTheme.typography.labelMedium)
                Switch(
                    checked = isAdvanced,
                    onCheckedChange = {
                        isAdvanced = it
                        // Reset all input sources when switching modes
                        selectedUri = null
                        fileName = ""
                        fps = ""
                        width = ""
                        height = ""
                        parts = listOf(
                            AnimationPartState(type = "p", repeats = "0", delay = "0", folder = "part0"),
                            AnimationPartState(type = "p", repeats = "0", delay = "0", folder = "part1"),
                            AnimationPartState(type = "p", repeats = "0", delay = "0", folder = "part2")
                        )
                    },
                    modifier = Modifier.scale(0.8f).padding(start = 4.dp)
                )
            }
        }

        if (isAdvanced) {
            // Advanced Layout: All in one line
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = fps,
                    onValueChange = { fps = it },
                    label = { Text("FPS", fontSize = 10.sp) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = width,
                    onValueChange = { width = it },
                    label = { Text("Width", fontSize = 10.sp) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it },
                    label = { Text("Height", fontSize = 10.sp) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sequence Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Sequences",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row {
                    IconButton(onClick = {
                        parts = parts.toMutableList().also { it.add(AnimationPartState(folder = "part${parts.size}")) }
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add line")
                    }
                    IconButton(
                        onClick = { if (parts.size > 1) parts = parts.dropLast(1) },
                        enabled = parts.size > 1
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove line")
                    }
                }
            }

            // Sequence Lines
            parts.forEachIndexed { index, part ->
                key(index) {
                    SequenceLine(
                        index = index,
                        part = part,
                        onUpdate = { updatedPart ->
                            parts = parts.toMutableList().also { it[index] = updatedPart }
                        },
                        onPickMedia = {
                            activePartIndex = index
                            isPickingAudio = false
                            showSourceDialog = true
                        },
                        onPickAudio = {
                            activePartIndex = index
                            isPickingAudio = true
                            audioLauncher.launch(arrayOf("audio/*"))
                        }
                    )
                }
            }
        } else {
            // Standard Layout
            OutlinedTextField(
                value = fps,
                onValueChange = { fps = it },
                label = { Text("Frames Per Second (FPS)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
                leadingIcon = { Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(20.dp)) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Resolution Inputs (Width x Height)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = width,
                    onValueChange = { width = it },
                    label = { Text("Width") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it },
                    label = { Text("Height") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Zip Size Estimation based on extracted frame samples
        val estimatedSizeMB = remember(width, height, fps, parts, selectedUri, isAdvanced, avgFrameSizeAtSource, sourceDuration, sourceWidth, sourceHeight) {
            val targetW = width.toDoubleOrNull() ?: 0.0
            val targetH = height.toDoubleOrNull() ?: 0.0
            val targetFps = fps.toDoubleOrNull() ?: 0.0

            if (isAdvanced) {
                parts.sumOf { part ->
                    if (part.uri == null || part.avgFrameSize == 0L) return@sumOf 0.0
                    val totalFrames = (part.durationMs / 1000.0) * targetFps
                    val areaRatio = if (part.sourceWidth > 0 && part.sourceHeight > 0) {
                        (targetW * targetH) / (part.sourceWidth * part.sourceHeight)
                    } else 1.0

                    (part.avgFrameSize * areaRatio * totalFrames) / (1024.0 * 1024.0)
                }
            } else {
                if (selectedUri == null || avgFrameSizeAtSource == 0L) return@remember 0.0
                val totalFrames = (sourceDuration / 1000.0) * targetFps
                val areaRatio = if (sourceWidth > 0 && sourceHeight > 0) {
                    (targetW * targetH) / (sourceWidth * sourceHeight)
                } else 1.0

                (avgFrameSizeAtSource * areaRatio * totalFrames) / (1024.0 * 1024.0)
            }
        }

        if (estimatedSizeMB > 0) {
            Text(
                text = "Estimated size: ${String.format(Locale.getDefault(), "%.2f", estimatedSizeMB)} MB",
                style = MaterialTheme.typography.labelMedium,
                color = if (estimatedSizeMB > MAX_ZIP_SIZE_MB) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // 3. Generate Button
        Button(
            onClick = {
                if (!FFmpegDownloader.isInstalled(context)) {
                    showFFmpegDialog = true
                } else if (estimatedSizeMB > MAX_ZIP_SIZE_MB) {
                    validationError = "The estimated ZIP size (${String.format(Locale.getDefault(), "%.2f", estimatedSizeMB)} MB) exceeds the $MAX_ZIP_SIZE_MB MB limit. At high resolutions, boot animations become massive very quickly. Try lowering the resolution or FPS."
                } else {
                    if (isAdvanced) {
                        // Validation for Advanced Mode
                        var errorMsg: String? = null
                        var warningMsg: String? = null

                        for (part in parts) {
                            if (part.uri == null) continue

                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(context, part.uri)
                                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

                                if (part.type == "c") {
                                    if (durationMs > 10000) {
                                        errorMsg = "Part \"${part.folder}\" uses 'complete' type with a video longer than 10 seconds. This will block the boot process until the video finishes."
                                        break
                                    } else if (durationMs > 5000) {
                                        warningMsg = "Part \"${part.folder}\" uses 'complete' type with a video longer than 5 seconds. This might significantly slow down your boot time. Do you want to proceed?"
                                    }
                                }
                            } finally {
                                retriever.release()
                            }
                        }

                        if (errorMsg != null) {
                            validationError = errorMsg
                        } else if (warningMsg != null) {
                            validationWarning = warningMsg
                        } else {
                            startAdvancedGeneration()
                        }
                    } else {
                        scope.launch {
                            generateBootAnimation(
                                context, selectedUri, fileName, fps, width, height,
                                onProgress = { status: String, progress: Float ->
                                    generationStatus = status
                                    generationProgress = progress
                                },
                                onStart = { isGenerating = true },
                                onComplete = { success: Boolean ->
                                    isGenerating = false
                                    if (!success) {
                                        Toast.makeText(context, "Failed to create animation.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            enabled = if (isAdvanced) parts.all { it.uri != null } && !isGenerating else selectedUri != null && !isGenerating
        ) {
            if (isGenerating) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = if (isGenerating) "Generating..." else "Generate Boot Animation",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showFFmpegDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDownloadingFFmpeg) showFFmpegDialog = false },
            title = { Text(if (isDownloadingFFmpeg) "Downloading FFmpeg" else "FFmpeg Required") },
            text = {
                Column {
                    Text(if (isDownloadingFFmpeg)
                        "Please wait while FFmpeg is being installed..."
                    else "FFmpeg is required to process and extract frames from videos.")

                    if (isDownloadingFFmpeg) {
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = ffmpegDownloadProgress,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${(ffmpegDownloadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.End
                        )
                    }
                }
            },
            confirmButton = {
                if (!isDownloadingFFmpeg) {
                    TextButton(onClick = {
                        isDownloadingFFmpeg = true
                        scope.launch {
                            val success = FFmpegDownloader.downloadAndInstall(context) { progress, message ->
                                ffmpegDownloadProgress = progress
                                if (message != null) ffmpegStatusMessage = message
                            }
                            isDownloadingFFmpeg = false
                            if (success) showFFmpegDialog = false
                        }
                    }) {
                        Text("Download")
                    }
                }
            },
            dismissButton = {
                if (!isDownloadingFFmpeg) {
                    TextButton(onClick = { showFFmpegDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    if (isGenerating) {
        AlertDialog(
            onDismissRequest = { },
            title = {
                Text(
                    text = "Creating Animation",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(generationStatus, textAlign = TextAlign.Center)
                }
            },
            confirmButton = {}
        )
    }

    if (showSourceDialog) {
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            title = { Text("Select Media Source") },
            text = { Text("Choose where you want to pick your video or GIF from.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSourceDialog = false
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    }
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Gallery")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSourceDialog = false
                        fileLauncher.launch(arrayOf("video/*", "image/gif"))
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Files")
                }
            }
        )
    }

    if (validationError != null) {
        AlertDialog(
            onDismissRequest = { validationError = null },
            title = { Text("Critical Warning") },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            text = { Text(validationError!!) },
            confirmButton = {
                Button(onClick = { validationError = null }) {
                    Text("I'll fix it")
                }
            }
        )
    }

    if (validationWarning != null) {
        AlertDialog(
            onDismissRequest = { validationWarning = null },
            title = { Text("Slow Boot Warning") },
            icon = { Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            text = { Text(validationWarning!!) },
            confirmButton = {
                Button(onClick = {
                    validationWarning = null
                    startAdvancedGeneration()
                }) {
                    Text("Proceed Anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { validationWarning = null }) {
                    Text("Go Back")
                }
            }
        )
    }
}

private suspend fun generateBootAnimation(
    context: android.content.Context,
    uri: Uri?,
    name: String,
    fps: String,
    width: String,
    height: String,
    onProgress: (String, Float) -> Unit,
    onStart: () -> Unit,
    onComplete: (Boolean) -> Unit
) {
    if (uri == null) return
    onStart()

    // Initialize FFmpeg library path
    utils.FFmpegDownloader.initLoader(context)

    withContext(Dispatchers.IO) {
        try {
            val workDir = File(context.cacheDir, "generation_work")
            if (workDir.exists()) workDir.deleteRecursively()
            workDir.mkdirs()

            val part0Dir = File(workDir, "part0")
            part0Dir.mkdirs()

            onProgress("Extracting frames...", 0.1f)

            // 1. Prepare video source (copy URI to temp file if needed)
            val sourceFile = File(workDir, "source_video")
            context.contentResolver.openInputStream(uri)?.use { input ->
                sourceFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // 2. FFmpeg Command to resize and extract frames
            // -vf scale=w:h,fps=fps
            val scaleFilter = if (width.isNotEmpty() && height.isNotEmpty()) "scale=$width:$height," else ""
            val videoFps = if (fps.isNotEmpty()) fps else "30"

            val extractCommand = "-y -i ${sourceFile.absolutePath} -vf \"${scaleFilter}fps=$videoFps\" ${part0Dir.absolutePath}/%05d.png"

            val session = FFmpegKit.execute(extractCommand)
            if (!ReturnCode.isSuccess(session.returnCode)) {
                withContext(Dispatchers.Main) { onComplete(false) }
                return@withContext
            }

            onProgress("Generating metadata...", 0.7f)
            // 3. Create desc.txt
            val descFile = File(workDir, "desc.txt")
            val descContent = "${width.ifEmpty { "1080" }} ${height.ifEmpty { "2400" }} $videoFps\np 0 0 part0\n"
            descFile.writeText(descContent)

            onProgress("Zipping animation...", 0.9f)
            // 4. Zip without compression
            val libraryDir = File(context.filesDir, "library")
            if (!libraryDir.exists()) libraryDir.mkdirs()

            val safeName = name.replace(Regex("[^a-zA-Z0-9]"), "_").ifEmpty { "custom_animation" }
            val outputFile = File(libraryDir, "${safeName}_${System.currentTimeMillis()}.zip")

            val zipSuccess = ZipUtils.zipBootAnimation(part0Dir, descFile, outputFile)

            // Clean up
            workDir.deleteRecursively()

            withContext(Dispatchers.Main) {
                onComplete(zipSuccess)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) { onComplete(false) }
        }
    }
}

private suspend fun generateAdvancedBootAnimation(
    context: android.content.Context,
    parts: List<AnimationPartState>,
    fps: String,
    width: String,
    height: String,
    onProgress: (String, Float) -> Unit,
    onStart: () -> Unit,
    onComplete: (Boolean) -> Unit
) {
    onStart()
    utils.FFmpegDownloader.initLoader(context)

    withContext(Dispatchers.IO) {
        try {
            val workDir = File(context.cacheDir, "generation_work_advanced")
            if (workDir.exists()) workDir.deleteRecursively()
            workDir.mkdirs()

            val videoFps = if (fps.isNotEmpty()) fps else "30"
            val targetWidth = if (width.isNotEmpty()) width else "1080"
            val targetHeight = if (height.isNotEmpty()) height else "2400"

            val descContent = StringBuilder()
            descContent.append("$targetWidth $targetHeight $videoFps\n")

            parts.forEachIndexed { index, part ->
                if (part.uri == null) return@forEachIndexed

                onProgress("Processing part ${part.folder}...", (index.toFloat() / parts.size) * 0.8f)

                val partDir = File(workDir, part.folder)
                partDir.mkdirs()

                val sourceFile = File(workDir, "temp_source_$index")
                context.contentResolver.openInputStream(part.uri)?.use { input ->
                    sourceFile.outputStream().use { output -> input.copyTo(output) }
                }

                val scaleFilter = "scale=$targetWidth:$targetHeight"
                val extractCommand = "-y -i ${sourceFile.absolutePath} -vf \"${scaleFilter},fps=$videoFps\" ${partDir.absolutePath}/%05d.png"

                val session = FFmpegKit.execute(extractCommand)
                if (!ReturnCode.isSuccess(session.returnCode)) {
                    withContext(Dispatchers.Main) { onComplete(false) }
                    return@withContext
                }

                val type = part.type.lowercase()
                val repeats = if (part.repeats.isEmpty()) "0" else part.repeats
                val delay = if (part.delay.isEmpty()) "0" else part.delay
                descContent.append("$type $repeats $delay ${part.folder}\n")

                // Handle Audio
                part.audioUri?.let { audioUri ->
                    val audioSourceFile = File(workDir, "temp_audio_$index")
                    context.contentResolver.openInputStream(audioUri)?.use { input ->
                        audioSourceFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    
                    val audioTargetFile = File(partDir, "audio.wav")
                    // Convert to wav if needed, or just copy if already wav
                    val extension = context.contentResolver.getType(audioUri)?.split("/")?.last() ?: "wav"
                    if (extension == "wav" || extension == "x-wav") {
                        audioSourceFile.copyTo(audioTargetFile, overwrite = true)
                    } else {
                        // Use ffmpeg to convert to wav
                        val convertCommand = "-y -i ${audioSourceFile.absolutePath} ${audioTargetFile.absolutePath}"
                        FFmpegKit.execute(convertCommand)
                    }
                }
            }

            onProgress("Generating metadata...", 0.9f)
            val descFile = File(workDir, "desc.txt")
            descFile.writeText(descContent.toString())

            onProgress("Zipping animation...", 0.95f)
            val libraryDir = File(context.filesDir, "library")
            if (!libraryDir.exists()) libraryDir.mkdirs()

            val outputFile = File(libraryDir, "advanced_animation_${System.currentTimeMillis()}.zip")

            // We need a Zip utility that can zip multiple folders and a file
            // Let's check ZipUtils.kt
            val zipSuccess = ZipUtils.zipAdvancedBootAnimation(workDir, descFile, outputFile)

            workDir.deleteRecursively()

            withContext(Dispatchers.Main) {
                onComplete(zipSuccess)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) { onComplete(false) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SequenceLine(
    index: Int,
    part: AnimationPartState,
    onUpdate: (AnimationPartState) -> Unit,
    onPickMedia: () -> Unit,
    onPickAudio: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                RoundedCornerShape(12.dp)
            )
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type (c/p) - Dropdown
            var expanded by remember { mutableStateOf(false) }

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.weight(0.7f)
            ) {
                OutlinedTextField(
                    value = part.type.uppercase(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Type", fontSize = 9.sp) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.width(160.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text("C (Complete)", softWrap = false) },
                        onClick = {
                            onUpdate(part.copy(type = "c"))
                            expanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("P (Partial)", softWrap = false) },
                        onClick = {
                            onUpdate(part.copy(type = "p"))
                            expanded = false
                        }
                    )
                }
            }

            // Repeats
            OutlinedTextField(
                value = part.repeats,
                onValueChange = { onUpdate(part.copy(repeats = it)) },
                label = { Text("Loop", fontSize = 9.sp) },
                modifier = Modifier.weight(0.7f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(8.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
            )
            // Delay
            OutlinedTextField(
                value = part.delay,
                onValueChange = { onUpdate(part.copy(delay = it)) },
                label = { Text("Delay", fontSize = 9.sp) },
                modifier = Modifier.weight(0.7f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(8.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
            )
            // Folder
            OutlinedTextField(
                value = part.folder,
                onValueChange = { if (it.length <= 6) onUpdate(part.copy(folder = it)) },
                label = { Text("Folder", fontSize = 9.sp) },
                modifier = Modifier.weight(0.85f),
                shape = RoundedCornerShape(8.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
            )

            // Media Picker for this part
            Box(
                modifier = Modifier
                    .weight(0.85f)
                    .clickable { onPickMedia() }
            ) {
                OutlinedTextField(
                    value = part.uri?.path?.split("/")?.last() ?: "Select",
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    label = { Text("Video", fontSize = 9.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledContainerColor = Color.Transparent
                    )
                )
            }

            // Audio Picker for this part
            Box(
                modifier = Modifier
                    .weight(0.85f)
                    .clickable { onPickAudio() }
            ) {
                OutlinedTextField(
                    value = part.audioUri?.path?.split("/")?.last() ?: "Optional",
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    label = { Text("Audio", fontSize = 9.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = if (part.audioUri == null) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledContainerColor = Color.Transparent
                    )
                )
            }
        }
    }
}

data class AnimationPartState(
    val type: String = "p",
    val repeats: String = "0",
    val delay: String = "0",
    val folder: String = "",
    val uri: Uri? = null,
    val audioUri: Uri? = null,
    val durationMs: Long = 0L,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val sourceFps: Double = 0.0,
    val sourceSize: Long = 0L,
    val avgFrameSize: Long = 0L
)
