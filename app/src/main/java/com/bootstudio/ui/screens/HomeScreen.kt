package com.bootstudio.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import utils.BootAnimParser
import utils.CommandExecutor
import utils.DiagnosticLogger
import utils.ModuleManager
import com.bootstudio.ui.components.VideoPreview
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
fun HomeScreen(
    currentPaths: List<String>,
    animations: SnapshotStateList<BootAnimation>,
    isLoading: Boolean,
    refreshTrigger: Int = 0,
    onRefreshRequest: () -> Unit = {},
    onPreview: (String) -> Unit = {},
    onSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playingAnim by remember { mutableStateOf<BootAnimation?>(null) }
    var systemAnimToUse by remember { mutableStateOf<String?>(null) }
    var savedSystemPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var appliedPath by remember { mutableStateOf<String?>(null) }

    var showPathSelectionDialog by remember { mutableStateOf(false) }

    // Actions State
    var showActionDialog by remember { mutableStateOf<BootAnimation?>(null) }
    var showRenameDialog by remember { mutableStateOf<BootAnimation?>(null) }
    var showDetailsDialog by remember { mutableStateOf<BootAnimation?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<BootAnimation?>(null) }
    var showRebootConfirmDialog by remember { mutableStateOf(false) }
    var exportingAnim by remember { mutableStateOf<BootAnimation?>(null) }
    var newName by remember { mutableStateOf("") }
    var applyingPath by remember { mutableStateOf<String?>(null) }

    // Track ongoing FFmpeg generations to avoid duplicates
    val generatingPaths = remember { mutableSetOf<String>() }

    val updateMetadata: suspend () -> Unit = {
        val prefs = context.getSharedPreferences("bootstudio_prefs", android.content.Context.MODE_PRIVATE)
        savedSystemPaths = currentPaths
        appliedPath = prefs.getString("applied_anim_path", "system_default")


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
        } else if (currentPaths.isNotEmpty()) {
            val systemPath = currentPaths.first()
            val moduleRoot = "/data/adb/modules/BootStudio"
            val originalDir = "$moduleRoot/original"
            val prioritizedBackup = "$originalDir/bootanimation.zip"
            
            val source = withContext(Dispatchers.IO) {
                if (CommandExecutor.executeWithSu("[ -f \"$prioritizedBackup\" ] && echo yes").contains("yes")) {
                    prioritizedBackup
                } else {
                    val firstFile = CommandExecutor.executeWithSu("ls \"$originalDir\" | head -n 1").trim()
                    if (firstFile.isNotEmpty() && !firstFile.startsWith("Error:")) {
                        "$originalDir/$firstFile"
                    } else {
                        systemPath
                    }
                }
            }
            localSystemPath = prepareSystemAnim(source)
        }
        systemAnimToUse = localSystemPath

        withContext(Dispatchers.IO) {
            val previewDir = File(context.cacheDir, "previews")
            if (!previewDir.exists()) previewDir.mkdirs()

            // Update previews and resolution for all animations in the list
            val itemsToProcess = animations.toList()
            itemsToProcess.forEach { anim ->
                // Skip if already generating
                if (generatingPaths.contains(anim.path)) return@forEach

                val procPath = if (anim.tag == "System") systemAnimToUse ?: anim.path else anim.path
                
                // Safety check for file existence
                if (anim.tag == "System" && systemAnimToUse == null) return@forEach
                
                val fileToParse = if (anim.isAsset) null else File(procPath)
                val desc = if (anim.isAsset) BootAnimParser.parseDescFromAssets(context, anim.path) else BootAnimParser.parseDesc(fileToParse!!)

                val previewFileName = if (anim.isAsset) {
                    anim.path.replace("/", "_") + ".mp4"
                } else if (anim.tag == "System") {
                    val backupFileName = anim.path.trimStart('/').replace('/', '_')
                    "original_${backupFileName}.mp4"
                } else if (anim.tag == "Community") {
                    "${File(anim.path).name}.mp4"
                } else {
                    val file = File(procPath)
                    "${file.nameWithoutExtension}_${file.length()}_${file.lastModified()}.mp4"
                }
                val previewFile = File(previewDir, previewFileName)

                if (!previewFile.exists()) {
                    generatingPaths.add(anim.path)
                    val onComplete: (Boolean) -> Unit = { success ->
                        generatingPaths.remove(anim.path)
                        scope.launch {
                            val currentIdx = animations.indexOfFirst { it.path == anim.path }
                            if (currentIdx != -1) {
                                animations[currentIdx] = animations[currentIdx].copy(
                                    previewUri = if (success) Uri.fromFile(previewFile) else null,
                                    generationFailed = !success
                                )
                            }
                        }
                    }
                    if (anim.isAsset) {
                        BootAnimParser.generatePreviewMp4FromAssets(context, anim.path, previewFile, onComplete)
                    } else {
                        BootAnimParser.generatePreviewMp4(context, File(procPath), previewFile, onComplete)
                    }
                }

                withContext(Dispatchers.Main) {
                    val currentIdx = animations.indexOfFirst { it.path == anim.path }
                    if (currentIdx != -1) {
                        animations[currentIdx] = animations[currentIdx].copy(
                            resolution = desc?.let { "${it.width}x${it.height}" },
                            isNonStandard = desc?.isStandard == false,
                            previewUri = if (previewFile.exists()) Uri.fromFile(previewFile) else animations[currentIdx].previewUri
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
                var importSuccess = false
                var errorMessage = ""

                withContext(Dispatchers.IO) {
                    val tempFile = File(context.cacheDir, "import_check_${System.currentTimeMillis()}.zip")
                    try {
                        context.contentResolver.openInputStream(it)?.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                        // Validate the animation
                        val desc = BootAnimParser.parseDesc(tempFile)
                        if (desc == null) {
                            errorMessage = "Invalid bootanimation: desc.txt is missing or malformed"
                            tempFile.delete()
                            return@withContext
                        }

                        val libraryDir = File(context.filesDir, "library")
                        if (!libraryDir.exists()) libraryDir.mkdirs()

                        val name = context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            cursor.moveToFirst()
                            cursor.getString(nameIndex)
                        } ?: "imported_${System.currentTimeMillis()}.zip"

                        val targetFile = File(libraryDir, name)
                        tempFile.renameTo(targetFile)

                        // Save metadata for imported animation
                        context.getSharedPreferences("anim_metadata", android.content.Context.MODE_PRIVATE)
                            .edit()
                            .putString("${targetFile.name}_tag", "Imported")
                            .putString("${targetFile.name}_creator", "Unknown")
                            .apply()

                        importSuccess = true
                    } catch (e: Exception) {
                        errorMessage = "Import failed: ${e.message}"
                        tempFile.delete()
                    }
                }

                if (importSuccess) {
                    onRefreshRequest()
                } else {
                }
            }
        }
    }

    LaunchedEffect(animations.size, refreshTrigger) {
        updateMetadata()
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
                Column {
                    Text(
                        text = "My Library",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        val uniqueFolders = currentPaths.map { File(it).parent ?: "/" }.distinct()
                        if (uniqueFolders.size > 1) {
                            showPathSelectionDialog = true
                        } else {
                            val folderPath = uniqueFolders.firstOrNull() ?: "/"
                            scope.launch(Dispatchers.IO) {
                                CommandExecutor.executeWithSu("am start -a android.intent.action.VIEW -d \"file://$folderPath\"", "Open File Explorer")
                            }
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Open Path")
                    }
                    IconButton(onClick = {
                        val modulePath = "/data/adb/modules/BootStudio"
                        scope.launch(Dispatchers.IO) {
                            val result = CommandExecutor.executeWithSu("am start -a android.intent.action.VIEW -d \"file://$modulePath\"", "Open Module Folder")
                            if (result.startsWith("Error:")) {
                                withContext(Dispatchers.Main) {
                                }
                            }
                        }
                    }) {
                        Icon(Icons.Default.Build, contentDescription = "Open Module")
                    }
                    IconButton(onClick = { showRebootConfirmDialog = true }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reboot")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            }

            if (isLoading && animations.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (animations.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No animations found. Create one or import a ZIP to get started!", color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                }
            } else {
                // Increase the viewport cache to keep off-screen items initialized and playing
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 100.dp), // Extra padding for pre-loading
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(animations, key = { it.path }) { anim ->
                        val isApplied = remember(appliedPath, anim.path, anim.tag) {
                            if (anim.tag == "System") {
                                appliedPath == "system_default"
                            } else {
                                appliedPath == anim.path
                            }
                        }

                        AnimationCard(
                            animation = anim,
                            isApplied = isApplied,
                            isApplying = applyingPath == anim.path,
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
                                if (!isApplied && applyingPath == null) {
                                    scope.launch {
                                        val currentPrefs = context.getSharedPreferences("bootstudio_prefs", android.content.Context.MODE_PRIVATE)
                                        val targetPaths = savedSystemPaths

                                        applyingPath = anim.path

                                        try {
                                            withContext(Dispatchers.IO) {
                                                if (anim.tag == "System") {
                                                    val result = ModuleManager.setDefaultAnimation(targetPaths)
                                                    if (result.isNotEmpty() && (result.startsWith("Error:") || result.contains("failed", true) || result.contains("denied", true))) {
                                                        throw Exception(result)
                                                    }
                                                } else {
                                                    val sourcePath = if (anim.isAsset) {
                                                        val file = File(context.cacheDir, anim.path.split("/").last())
                                                        context.assets.open(anim.path).use { input ->
                                                            file.outputStream().use { output -> input.copyTo(output) }
                                                        }
                                                        file.absolutePath
                                                    } else {
                                                        anim.path
                                                    }
                                                    val result = ModuleManager.changeBootAnimation(sourcePath, targetPaths)
                                                    if (result.isNotEmpty() && (result.startsWith("Error:") || result.contains("failed", true) || result.contains("denied", true))) {
                                                        throw Exception(result)
                                                    }
                                                }
                                            }

                                            // Update state on Main thread
                                            if (anim.tag == "System") {
                                                appliedPath = "system_default"
                                                currentPrefs.edit().putString("applied_anim_path", "system_default").apply()
                                            } else {
                                                appliedPath = anim.path
                                                currentPrefs.edit().putString("applied_anim_path", anim.path).apply()
                                            }

                                        } catch (e: Exception) {
                                            DiagnosticLogger.log("ui", "Apply Error", e.message ?: "Unknown error")
                                        } finally {
                                            applyingPath = null
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

        if (showRebootConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showRebootConfirmDialog = false },
                title = { Text("Reboot Device") },
                text = { Text("Are you sure you want to reboot your device?") },
                confirmButton = {
                    Button(
                        onClick = {
                            showRebootConfirmDialog = false
                            scope.launch(Dispatchers.IO) {
                                CommandExecutor.executeWithSu("reboot", "Reboot")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Reboot")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRebootConfirmDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

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
                                        ModuleManager.setDefaultAnimation(savedSystemPaths)
                                        context.getSharedPreferences("bootstudio_prefs", android.content.Context.MODE_PRIVATE)
                                            .edit().putString("applied_anim_path", "system_default").apply()
                                    }
                                }
                                onRefreshRequest()
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
                            onRefreshRequest()
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

        if (showPathSelectionDialog) {
            val uniqueFolders = remember(currentPaths) {
                currentPaths.map { File(it).parent ?: "/" }.distinct()
            }
            AlertDialog(
                onDismissRequest = { showPathSelectionDialog = false },
                title = { Text("Select Folder to Open") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        uniqueFolders.forEach { folderPath ->
                            TextButton(
                                onClick = {
                                    showPathSelectionDialog = false
                                    scope.launch(Dispatchers.IO) {
                                        CommandExecutor.executeWithSu("am start -a android.intent.action.VIEW -d \"file://$folderPath\"", "Open File Explorer")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(folderPath, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPathSelectionDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
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
    onPreviewFailed: () -> Unit = {},
    isApplying: Boolean = false
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
            // 1. Preview MP4 with Play Button fallback
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .clickable { onPlay() }
            ) {
                androidx.compose.animation.Crossfade(
                    targetState = animation,
                    label = "preview_fade"
                ) { animState ->
                    val uri = animState.previewUri
                    when {
                        uri != null && !animState.generationFailed -> VideoPreview(uri, onLoadingFailed = onPreviewFailed)
                        animState.generationFailed -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.5f))
                        }
                        else -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
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
            if (isApplying) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (isApplied) {
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
