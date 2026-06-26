package com.bootstudio.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.widget.ImageView
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Edit
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
import utils.DiagnosticLogger
import utils.FFmpegDownloader
import utils.MagiskManager
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BootAnimation(
    val name: String,
    val path: String,
    val isAsset: Boolean,
    val tag: String? = null,
    val creator: String? = null,
    val previewUri: Uri? = null,
    val resolution: String? = null,
    val isNonStandard: Boolean = false,
    val generationFailed: Boolean = false
)

@Composable
fun HomeScreen(onPreview: (String) -> Unit = {}, onSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val animations = remember { mutableStateListOf<BootAnimation>() }
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
    var showSettingsMenu by remember { mutableStateOf(false) }

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
                if (!target.exists()) {
                    CommandExecutor.executeWithSu("cp \"$sourcePath\" \"${target.absolutePath}\" && chmod 644 \"${target.absolutePath}\"", purpose = "Copy system bootanim")
                }
                if (target.exists()) target.absolutePath else null
            }
        }

        var localSystemPath: String? = null
        val cachedSystemFile = File(context.cacheDir, "system_backup.zip")

        if (cachedSystemFile.exists()) {
            localSystemPath = cachedSystemFile.absolutePath
        } else if (systemPath != null) {
            val moduleRoot = "/data/adb/modules/BootStudio"
            val backupFileName = systemPath.trimStart('/').replace('/', '_')
            val backupPath = "$moduleRoot/original/$backupFileName"
            val backupExists = withContext(Dispatchers.IO) {
                CommandExecutor.executeWithSu("[ -f \"$backupPath\" ] && echo \"exists\"", purpose = "checking backup").contains("exists")
            }
            if (backupExists) {
                localSystemPath = prepareSystemAnim(backupPath)
            } else {
                val detectedExists = withContext(Dispatchers.IO) {
                    CommandExecutor.executeWithSu("[ -f \"$systemPath\" ] && echo \"exists\"", purpose = "checking system path").contains("exists")
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
            val metaPrefs = context.getSharedPreferences("anim_metadata", android.content.Context.MODE_PRIVATE)
            libraryDir.listFiles()?.filter { it.extension == "zip" }?.forEach { file ->
                val fileName = file.name
                var tag = metaPrefs.getString("${fileName}_tag", "Created") ?: "Created"
                if (tag == "Downloaded") tag = "Community"
                val creator = metaPrefs.getString("${fileName}_creator", null)
                initialAnims.add(BootAnimation(
                    name = file.nameWithoutExtension,
                    path = file.absolutePath,
                    isAsset = false,
                    tag = tag,
                    creator = creator
                ))
            }
        }
        animations.clear()
        animations.addAll(initialAnims)

        // Metadata loading
        withContext(Dispatchers.IO) {
            if (FFmpegDownloader.isInstalled(context)) FFmpegDownloader.initLoader(context)
            val previewDir = File(context.filesDir, "previews")
            if (!previewDir.exists()) previewDir.mkdirs()

            initialAnims.forEachIndexed { index, anim ->
                val procPath = if (anim.tag == "System") systemAnimToUse ?: anim.path else anim.path
                val fileToParse = if (anim.isAsset) null else File(procPath)
                val desc = if (anim.isAsset) BootAnimParser.parseDescFromAssets(context, anim.path) else BootAnimParser.parseDesc(fileToParse!!)

                // GIF Preview Generation (v4 for OOM fix and better folder matching)
                val previewFileName = if (anim.isAsset) {
                    anim.path.replace("/", "_") + "_v4.gif"
                } else if (anim.tag == "System") {
                    // For system animation, use a name that identifies it as the original backup
                    val backupFileName = anim.path.trimStart('/').replace('/', '_')
                    "original_${backupFileName}_v4.gif"
                } else if (anim.tag == "Community") {
                    // For community animations, use a fixed name based on the filename
                    "${File(anim.path).name}_v4.gif"
                } else {
                    val file = File(procPath)
                    "${file.nameWithoutExtension}_${file.length()}_${file.lastModified()}_v4.gif"
                }
                val previewFile = File(previewDir, previewFileName)

                if (!previewFile.exists()) {
                    val onComplete: (Boolean) -> Unit = { success ->
                        scope.launch {
                            val idx = animations.indexOfFirst { it.path == anim.path }
                            if (idx != -1) {
                                animations[idx] = animations[idx].copy(
                                    previewUri = if (success) Uri.fromFile(previewFile) else null,
                                    generationFailed = !success
                                )
                            }
                        }
                    }
                    if (anim.isAsset) {
                        BootAnimParser.generatePreviewGifFromAssets(context, anim.path, previewFile, onComplete)
                    } else {
                        BootAnimParser.generatePreviewGif(context, File(procPath), previewFile, onComplete)
                    }
                }

                withContext(Dispatchers.Main) {
                    val idx = animations.indexOfFirst { it.path == anim.path }
                    if (idx != -1) {
                        animations[idx] = animations[idx].copy(
                            resolution = desc?.let { "${it.width}x${it.height}" },
                            isNonStandard = desc?.isStandard == false,
                            previewUri = if (previewFile.exists()) Uri.fromFile(previewFile) else animations[idx].previewUri
                        )
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
                            // Export successful
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
                    // Save metadata for imported animation
                    context.getSharedPreferences("anim_metadata", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putString("${targetFile.name}_tag", "Imported")
                        .putString("${targetFile.name}_creator", "Unknown")
                        .apply()
                }
                loadAnimations()
                // Imported successfully
            }
        }
    }

    LaunchedEffect(Unit) {
        DiagnosticLogger.init(context)
        withContext(Dispatchers.IO) { CommandExecutor.initRootSession() }
        loadAnimations()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "My Library",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Box {
                    IconButton(onClick = { showSettingsMenu = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    DropdownMenu(
                        expanded = showSettingsMenu,
                        onDismissRequest = { showSettingsMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = {
                                showSettingsMenu = false
                                onSettings()
                            },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) }
                        )
                    }
                }
            }

            if (animations.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No animations found in assets/bootanimations/", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(animations, key = { it.path }) { anim ->
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
                                if (!isApplied) {
                                    scope.launch {
                                        val currentPrefs = context.getSharedPreferences("bootstudio_prefs", android.content.Context.MODE_PRIVATE)
                                        val currentPath = savedSystemPath ?: return@launch

                                        if (anim.tag == "System") {
                                            withContext(Dispatchers.IO) { MagiskManager.setDefaultAnimation(currentPath) }
                                            appliedPath = "system_default"
                                            currentPrefs.edit().putString("applied_anim_path", "system_default").apply()
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
                                        }
                                    }
                                }
                            },
                            onLongClick = { showActionDialog = anim },
                            onPreviewFailed = {
                                val idx = animations.indexOfFirst { it.path == anim.path }
                                if (idx != -1) {
                                    animations[idx] = animations[idx].copy(generationFailed = true)
                                }
                            }
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

                        if (anim.tag == "Created" || anim.tag == "Community" || anim.tag == "Imported") {
                            TextButton(onClick = {
                                newName = anim.name
                                showRenameDialog = anim
                                showActionDialog = null
                            }) { Text("Rename") }
                        }
                    }
                },
                dismissButton = {
                    if (anim.tag == "Created" || anim.tag == "Community" || anim.tag == "Imported") {
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
    onLongClick: () -> Unit,
    onPreviewFailed: () -> Unit = {}
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
            // 1. Preview GIF with Play Button fallback
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .clickable { onPlay() }
            ) {
                if (animation.previewUri != null && !animation.generationFailed) {
                    GifPreview(animation.previewUri, onLoadingFailed = onPreviewFailed)
                } else if (animation.generationFailed) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.5f))
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
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
                            animation.tag == "System" -> "System"
                            animation.isAsset -> "Built-in"
                            animation.creator != null -> animation.creator
                            animation.tag == "Created" -> "Me"
                            else -> "Unknown"
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
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = animation.tag,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
fun GifPreview(uri: Uri, onLoadingFailed: () -> Unit = {}) {
    val context = LocalContext.current
    val drawableState = remember(uri) { mutableStateOf<android.graphics.drawable.Drawable?>(null) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    val drawable = ImageDecoder.decodeDrawable(source)
                    if (drawable is AnimatedImageDrawable) {
                        drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                        drawable.start()
                    }
                    withContext(Dispatchers.Main) {
                        drawableState.value = drawable
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onLoadingFailed()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
            },
            update = { imageView ->
                val drawable = drawableState.value
                if (drawable != null) {
                    if (imageView.drawable != drawable) {
                        imageView.setImageDrawable(drawable)
                    }
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    imageView.setImageURI(uri)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (drawableState.value == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
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
            BootAnimParser.parseDesc(File(animation.path))
        } ?: return@LaunchedEffect

        animWidth = desc.width
        animHeight = desc.height
        val frameDuration = 1000L / desc.fps
        isLoaded = true

        val zipFileStream = if (animation.isAsset) {
            context.assets.open(animation.path)
        } else {
            File(animation.path).inputStream()
        }

        // On-the-fly frame extraction to prevent OOM
        withContext(Dispatchers.IO) {
            val zipData = if (animation.isAsset) {
                context.assets.open(animation.path).use { it.readBytes() }
            } else {
                File(animation.path).readBytes()
            }

            for (part in desc.parts) {
                val audioFile = if (animation.isAsset) {
                    BootAnimParser.getAudioForPartFromAssets(context, animation.path, part.folder)
                } else {
                    BootAnimParser.getAudioForPart(File(animation.path), part.folder, context)
                }

                val loopCount = if (part.loop == 0) 5 else part.loop
                repeat(loopCount) {
                    if (audioFile != null) {
                        withContext(Dispatchers.Main) {
                            audioPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(audioFile)))
                            audioPlayer.prepare()
                            audioPlayer.play()
                        }
                    }

                    ZipInputStream(zipData.inputStream()).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (entry.name.startsWith("${part.folder}/") &&
                                (entry.name.endsWith(".png", true) || entry.name.endsWith(".jpg", true) || entry.name.endsWith(".webp", true))
                            ) {
                                val bitmap = BitmapFactory.decodeStream(zip)
                                if (bitmap != null) {
                                    withContext(Dispatchers.Main) {
                                        currentFrame = bitmap
                                    }
                                    delay(frameDuration)
                                }
                            }
                            entry = zip.nextEntry
                        }
                    }
                    delay(part.pause * frameDuration)
                    withContext(Dispatchers.Main) { audioPlayer.stop() }
                }
                audioFile?.delete()
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
