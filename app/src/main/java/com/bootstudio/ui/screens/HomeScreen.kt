package com.bootstudio.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import java.util.zip.ZipFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    // Actions State
    var showActionDialog by remember { mutableStateOf<BootAnimation?>(null) }
    var showRenameDialog by remember { mutableStateOf<BootAnimation?>(null) }
    var showDetailsDialog by remember { mutableStateOf<BootAnimation?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<BootAnimation?>(null) }
    var exportingAnim by remember { mutableStateOf<BootAnimation?>(null) }
    var newName by remember { mutableStateOf("") }

    val loadAnimations: suspend () -> Unit = {
        val prefs = context.getSharedPreferences("bootstudio_prefs", android.content.Context.MODE_PRIVATE)
        val systemPath = prefs.getString("boot_anim_path", null)
        savedSystemPath = systemPath
        appliedPath = prefs.getString("applied_anim_path", "system_default")

        val assetList = context.assets.list("bootanimations") ?: emptyArray()
        val zipFiles = assetList.filter { it.startsWith("bootanimation_") && it.endsWith(".zip") }
        val initialAnims = mutableListOf<BootAnimation>()

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
            val backupExists = withContext(Dispatchers.IO) {
                CommandExecutor.executeWithSu("[ -f \"$backupPath\" ] && echo \"exists\"").contains("exists")
            }
            if (backupExists) {
                localSystemPath = prepareSystemAnim(backupPath)
            } else {
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
            initialAnims.add(BootAnimation(name = "System Animation", path = systemPath, isAsset = false, tag = "System"))
        }

        zipFiles.forEach { fileName ->
            val name = fileName.removePrefix("bootanimation_").removeSuffix(".zip")
            initialAnims.add(BootAnimation(name = name, path = "bootanimations/$fileName", isAsset = true, tag = "Built-in"))
        }

        val libraryDir = File(context.filesDir, "library")
        if (libraryDir.exists()) {
            libraryDir.listFiles()?.filter { it.extension == "zip" }?.forEach { file ->
                initialAnims.add(BootAnimation(name = file.nameWithoutExtension, path = file.absolutePath, isAsset = false, tag = "Created"))
            }
        }
        animations = initialAnims

        // Metadata loading
        withContext(Dispatchers.IO) {
            if (FFmpegDownloader.isInstalled(context)) FFmpegDownloader.initLoader(context)
            initialAnims.forEachIndexed { index, anim ->
                val procPath = if (anim.tag == "System") systemAnimToUse ?: anim.path else anim.path
                val fileToParse = if (anim.isAsset) null else File(procPath)
                val desc = if (anim.isAsset) BootAnimParser.parseDescFromAssets(context, anim.path) else BootAnimParser.parseDesc(context, fileToParse!!)
                val preview = if (anim.isAsset) BootAnimParser.getFirstFrame(context, anim.path) else BootAnimParser.getFirstFrameFromFile(context, fileToParse!!)
                withContext(Dispatchers.Main) {
                    animations = animations.toMutableList().also { list ->
                        if (index < list.size) {
                            list[index] = list[index].copy(preview = preview, resolution = desc?.let { "${it.width}x${it.height}" }, isNonStandard = desc?.isStandard == false)
                        }
                    }
                }
            }
        }
    }

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { targetUri ->
            val anim = exportingAnim ?: return@let
            scope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(targetUri)?.use { output ->
                            if (anim.isAsset) {
                                context.assets.open(anim.path).use { input ->
                                    input.copyTo(output)
                                }
                            } else {
                                val sourcePath = if (anim.tag == "System") systemAnimToUse ?: anim.path else anim.path
                                File(sourcePath).inputStream().use { input ->
                                    input.copyTo(output)
                                }
                            }
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Exported successfully!", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                withContext(Dispatchers.IO) {
                    val libraryDir = File(context.filesDir, "library")
                    if (!libraryDir.exists()) libraryDir.mkdirs()

                    val name = context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        cursor.moveToFirst()
                        cursor.getString(nameIndex)
                    } ?: "imported_${System.currentTimeMillis()}.zip"

                    val targetFile = File(libraryDir, name)
                    context.contentResolver.openInputStream(it)?.use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                loadAnimations()
                Toast.makeText(context, "Animation imported!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { CommandExecutor.initRootSession() }
        loadAnimations()
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
                                    val currentPath = savedSystemPath ?: return@launch

                                    if (isApplied) {
                                        // If already applied, clicking it again removes it (reverts to system)
                                        withContext(Dispatchers.IO) { MagiskManager.setDefaultAnimation(currentPath) }
                                        appliedPath = "system_default"
                                        currentPrefs.edit().putString("applied_anim_path", "system_default").apply()
                                        Toast.makeText(context, "System animation restored", Toast.LENGTH_SHORT).show()
                                    } else {
                                        if (anim.tag == "System") {
                                            withContext(Dispatchers.IO) { MagiskManager.setDefaultAnimation(currentPath) }
                                            appliedPath = "system_default"
                                            currentPrefs.edit().putString("applied_anim_path", "system_default").apply()
                                            Toast.makeText(context, "System animation selected", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val sourcePath = if (anim.isAsset) {
                                                val file = File(context.cacheDir, anim.path.split("/").last())
                                                withContext(Dispatchers.IO) {
                                                    context.assets.open(anim.path).use { input ->
                                                        file.outputStream().use { output -> input.copyTo(output) }
                                                    }
                                                }
                                                file.absolutePath
                                            } else {
                                                anim.path
                                            }
                                            withContext(Dispatchers.IO) { MagiskManager.changeBootAnimation(sourcePath, currentPath) }
                                            appliedPath = anim.path
                                            currentPrefs.edit().putString("applied_anim_path", anim.path).apply()
                                            Toast.makeText(context, "Animation applied", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            onLongClick = { showActionDialog = anim }
                        )
                    }
                }
            }
        }

        // Dialogs for Management
        if (showActionDialog != null) {
            val anim = showActionDialog!!
            AlertDialog(
                onDismissRequest = { showActionDialog = null },
                title = { Text("Manage Animation") },
                text = { Text("What would you like to do with \"${anim.name}\"?") },
                confirmButton = {
                    Row {
                        TextButton(onClick = {
                            showDetailsDialog = anim
                            showActionDialog = null
                        }) { Text("Details") }

                        TextButton(onClick = {
                            exportingAnim = anim
                            exportLauncher.launch("${anim.name}.zip")
                            showActionDialog = null
                        }) { Text("Export") }

                        if (anim.tag == "Created") {
                            TextButton(onClick = {
                                newName = anim.name
                                showRenameDialog = anim
                                showActionDialog = null
                            }) { Text("Rename") }
                        }
                    }
                },
                dismissButton = {
                    if (anim.tag == "Created") {
                        TextButton(onClick = {
                            showDeleteConfirmDialog = anim
                            showActionDialog = null
                        }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove") }
                    }
                }
            )
        }

        if (showDeleteConfirmDialog != null) {
            val anim = showDeleteConfirmDialog!!
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = null },
                title = { Text("Confirm Removal") },
                text = { Text("Are you sure you want to remove \"${anim.name}\"? This action cannot be undone.") },
                confirmButton = {
                    Button(
                        onClick = {
                            val filePath = anim.path
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val file = File(filePath)
                                    if (file.exists()) file.delete()

                                    if (filePath == appliedPath) {
                                        savedSystemPath?.let { sysPath ->
                                            MagiskManager.setDefaultAnimation(sysPath)
                                            context.getSharedPreferences("bootstudio_prefs", android.content.Context.MODE_PRIVATE)
                                                .edit().putString("applied_anim_path", "system_default").apply()
                                        }
                                    }
                                }
                                loadAnimations()
                            }
                            showDeleteConfirmDialog = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Remove") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmDialog = null }) { Text("Cancel") }
                }
            )
        }

        if (showRenameDialog != null) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = null },
                title = { Text("Rename Animation") },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New Name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        val animToRename = showRenameDialog
                        val oldPath = animToRename?.path ?: ""
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val oldFile = File(oldPath)
                                if (oldFile.exists()) {
                                    val newFile = File(oldFile.parent, "$newName.zip")
                                    val newPath = newFile.absolutePath
                                    if (oldFile.renameTo(newFile)) {
                                        if (oldPath == appliedPath) {
                                            context.getSharedPreferences("bootstudio_prefs", android.content.Context.MODE_PRIVATE)
                                                .edit().putString("applied_anim_path", newPath).apply()
                                        }
                                    }
                                }
                            }
                            loadAnimations()
                        }
                        showRenameDialog = null
                    }) { Text("Save") }
                }
            )
        }

        if (showDetailsDialog != null) {
            val anim = showDetailsDialog!!
            var descContent by remember { mutableStateOf("Loading...") }
            var fileSize by remember { mutableStateOf("") }
            var creationDate by remember { mutableStateOf("") }

            LaunchedEffect(anim) {
                withContext(Dispatchers.IO) {
                    try {
                        if (anim.isAsset) {
                            descContent = "Built-in Asset (Compressed)"
                        } else {
                            val file = File(anim.path)
                            fileSize = "${String.format(Locale.getDefault(), "%.2f", file.length() / (1024.0 * 1024.0))} MB"
                            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            creationDate = sdf.format(Date(file.lastModified()))

                            ZipFile(file).use { zip ->
                                val entry = zip.getEntry("desc.txt")
                                descContent = if (entry != null) {
                                    zip.getInputStream(entry).bufferedReader().readText()
                                } else {
                                    "desc.txt not found"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        descContent = "Error reading details: ${e.message}"
                    }
                }
            }

            AlertDialog(
                onDismissRequest = { showDetailsDialog = null },
                title = { Text("Animation Details") },
                text = {
                    Column {
                        Text("Name: ${anim.name}", fontWeight = FontWeight.Bold)
                        Text("File Size: $fileSize")
                        Text("Created: $creationDate")
                        Spacer(Modifier.height(8.dp))
                        Text("desc.txt Content:", fontWeight = FontWeight.Bold)
                        Surface(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = descContent,
                                modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showDetailsDialog = null }) {
                        Text("Close")
                    }
                }
            )
        }

        if (playingAnim != null) {
            BootAnimPlayer(playingAnim!!, onDismiss = { playingAnim = null })
        }

        FloatingActionButton(
            onClick = { importLauncher.launch(arrayOf("application/zip")) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(Icons.Default.Add, contentDescription = "Import ZIP")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnimationCard(
    animation: BootAnimation,
    isApplied: Boolean,
    onPlay: () -> Unit,
    onApply: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .combinedClickable(
                onClick = onApply,
                onLongClick = onLongClick
            ),
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

                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.Center).size(32.dp)
                )
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
                    val tagColor = when (animation.tag) {
                        "System" -> MaterialTheme.colorScheme.primaryContainer
                        "Built-in" -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val onTagColor = when (animation.tag) {
                        "System" -> MaterialTheme.colorScheme.onPrimaryContainer
                        "Built-in" -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Surface(
                        color = tagColor.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = animation.tag,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = onTagColor,
                            fontWeight = FontWeight.Bold
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
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer
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
    val dm = context.resources.displayMetrics
    val deviceWidthPx = dm.widthPixels.toFloat()
    val deviceHeightPx = dm.heightPixels.toFloat()

    val audioPlayer = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(Unit) {
        onDispose { audioPlayer.release() }
    }

    var currentFrame by remember { mutableStateOf<Bitmap?>(null) }
    var isLoaded by remember { mutableStateOf(false) }
    var animWidth by remember { mutableStateOf(0) }
    var animHeight by remember { mutableStateOf(0) }

    LaunchedEffect(animation) {
        val desc = if (animation.isAsset) {
            BootAnimParser.parseDescFromAssets(context, animation.path)
        } else {
            BootAnimParser.parseDesc(context, File(animation.path))
        } ?: return@LaunchedEffect

        animWidth = desc.width
        animHeight = desc.height
        val frameDuration = 1000L / desc.fps

        isLoaded = true
        for (part in desc.parts) {
            val frames = if (animation.isAsset) {
                BootAnimParser.getFramesForPartFromAssets(context, animation.path, part.folder)
            } else {
                BootAnimParser.getFramesForPart(File(animation.path), part.folder)
            }
            if (frames.isEmpty()) continue

            // Extract audio for this part
            val audioFile = if (animation.isAsset) {
                BootAnimParser.getAudioForPartFromAssets(context, animation.path, part.folder)
            } else {
                BootAnimParser.getAudioForPart(File(animation.path), part.folder, context)
            }

            val loopCount = if (part.loop == 0) 5 else part.loop
            repeat(loopCount) {
                // Play audio if it exists
                if (audioFile != null) {
                    withContext(Dispatchers.Main) {
                        audioPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(audioFile)))
                        audioPlayer.prepare()
                        audioPlayer.play()
                    }
                }

                for (frame in frames) {
                    currentFrame = frame
                    delay(frameDuration)
                }
                delay(part.pause * frameDuration)

                withContext(Dispatchers.Main) {
                    audioPlayer.stop()
                }
            }
            audioFile?.delete() // Cleanup temp audio file
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
                // Represent the animation at its actual scale relative to the device resolution
                // If the animation is larger than the screen, it will be cropped
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val animWidthPxValue = animWidth.toFloat()
                    val animHeightPxValue = animHeight.toFloat()

                    val displayWidth = maxWidth * (animWidthPxValue / deviceWidthPx)
                    val displayHeight = maxHeight * (animHeightPxValue / deviceHeightPx)

                    Image(
                        bitmap = currentFrame!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.requiredSize(displayWidth, displayHeight),
                        contentScale = ContentScale.FillBounds
                    )
                }
            } else if (!isLoaded) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}
