package com.bootstudio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.bootstudio.ui.theme.BootStudioTheme
import com.bootstudio.ui.screens.HomeScreen
import com.bootstudio.ui.screens.SetupScreen
import com.bootstudio.ui.screens.CreateScreen
import com.bootstudio.ui.screens.CommunityScreen
import com.bootstudio.ui.screens.PreviewScreen
import com.bootstudio.ui.screens.SettingsScreen
import com.bootstudio.ui.screens.ErrorScreen
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import java.io.File
import utils.BootAnimParser
import utils.CommandExecutor
import utils.DiagnosticLogger
import utils.ModuleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

    private var importTrigger = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLogger.init(this)
        BootAnimParser.cleanCache(this)
        com.arthenica.ffmpegkit.FFmpegKit.init(this)

        handleIntent(intent)

        val prefs = getSharedPreferences("bootstudio_prefs", MODE_PRIVATE)
        val initialPaths = prefs.getStringSet("boot_anim_paths", null)?.toList()

        enableEdgeToEdge()
        setContent {
            val scope = rememberCoroutineScope()
            val refreshCount by remember { importTrigger }
            BootStudioTheme {
                var currentPaths by remember { mutableStateOf(initialPaths) }
                var hasRoot by remember { mutableStateOf<Boolean?>(null) }

                LaunchedEffect(currentPaths) {
                    if (currentPaths != null) {
                        // Check for root if setup is already finished
                        withContext(Dispatchers.IO) {
                            val success = CommandExecutor.initRootSession()
                            hasRoot = success
                        }
                    }
                }

                if (currentPaths == null) {
                    SetupScreen(onSetupComplete = { selectedPaths, allFoundPaths ->
                        // Initial root check happens inside createMagiskModule or here
                        scope.launch {
                            val success = withContext(Dispatchers.IO) {
                                CommandExecutor.initRootSession()
                            }
                            if (success) {
                                withContext(Dispatchers.IO) {
                                    ModuleManager.createModule(selectedPaths)
                                }
                                prefs.edit()
                                    .putStringSet("boot_anim_paths", selectedPaths.toSet())
                                    .putStringSet("all_boot_anim_paths", allFoundPaths.toSet())
                                    .apply()
                                currentPaths = selectedPaths
                            } else {
                                hasRoot = false
                            }
                        }
                    })
                } else if (hasRoot == false) {
                    ErrorScreen(
                        title = "Root Access Required",
                        message = "BootStudio requires Superuser (root) permissions to modify system files and Magisk modules. Please grant access and try again.",
                        onRetry = {
                            hasRoot = null // Reset to trigger check again
                        }
                    )
                } else if (hasRoot == true) {
                    MainScreen(
                        currentPaths = currentPaths ?: emptyList(),
                        refreshTrigger = refreshCount,
                        onRefreshRequest = { importTrigger.intValue++ },
                        onPathsChange = { newPaths ->
                            val oldPaths = currentPaths
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    // 1. Revert the old paths so they are clean in Magisk
                                    if (oldPaths != null) {
                                        ModuleManager.setDefaultAnimation(oldPaths)
                                    }

                                    // 2. Clear cache for system animation to force re-extraction
                                    val cachedFile = File(cacheDir, "system_backup.zip")
                                    if (cachedFile.exists()) cachedFile.delete()

                                    // 3. Setup the new paths
                                    ModuleManager.createModule(newPaths)

                                    // 4. Clear the preview MP4 for the first new system path to force recreation
                                    val previewDir = File(cacheDir, "previews")
                                    val firstPath = newPaths.firstOrNull()
                                    if (firstPath != null) {
                                        val backupFileName = firstPath.trimStart('/').replace('/', '_')
                                        val previewFile = File(previewDir, "original_${backupFileName}.mp4")
                                        if (previewFile.exists()) previewFile.delete()
                                    }
                                }

                                // 5. Reset applied animation to system default for the new paths
                                prefs.edit()
                                    .putStringSet("boot_anim_paths", newPaths.toSet())
                                    .putString("applied_anim_path", "system_default")
                                    .apply()

                                currentPaths = newPaths
                            }
                        }
                    )
                }
                else {
                    // Loading or checking root
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                val tempFile = File(cacheDir, "import_intent_${System.currentTimeMillis()}.zip")
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    val desc = BootAnimParser.parseDesc(tempFile)
                    if (desc == null) {
                        tempFile.delete()
                        withContext(Dispatchers.Main) {
                        }
                        return@launch
                    }

                    val libraryDir = File(filesDir, "library")
                    if (!libraryDir.exists()) libraryDir.mkdirs()

                    val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        cursor.moveToFirst()
                        cursor.getString(nameIndex)
                    } ?: "imported_${System.currentTimeMillis()}.zip"

                    val targetFile = File(libraryDir, name)
                    tempFile.renameTo(targetFile)

                    getSharedPreferences("anim_metadata", MODE_PRIVATE)
                        .edit()
                        .putString("${targetFile.name}_tag", "Imported")
                        .putString("${targetFile.name}_creator", "External")
                        .apply()

                    withContext(Dispatchers.Main) {
                        importTrigger.intValue++
                    }
                } catch (e: Exception) {
                    tempFile.delete()
                    withContext(Dispatchers.Main) {
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    currentPaths: List<String>,
    refreshTrigger: Int = 0,
    onRefreshRequest: () -> Unit,
    onPathsChange: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val animations = remember { mutableStateListOf<com.bootstudio.ui.screens.BootAnimation>() }
    var isLoading by remember { mutableStateOf(false) }
    
    // Global loader for the entire MainScreen context
    val loadAnimations: suspend () -> Unit = {
        if (!isLoading) {
            isLoading = true
            try {
                val assetList = context.assets.list("bootanimations") ?: emptyArray<String>()
                val zipFiles = assetList.filter { it.startsWith("bootanimation_") && it.endsWith(".zip") }
                val initialAnims = mutableListOf<com.bootstudio.ui.screens.BootAnimation>()

                // 1. System Animation
                if (currentPaths.isNotEmpty()) {
                    val systemPath = currentPaths.first()
                    val target = File(context.cacheDir, "system_backup.zip")
                    val previewDir = File(context.cacheDir, "previews")
                    if (!previewDir.exists()) previewDir.mkdirs()
                    
                    withContext(Dispatchers.IO) {
                        if (!target.exists()) {
                            val originalDir = "/data/adb/modules/BootStudio/original"
                            val prioritizedBackup = "$originalDir/bootanimation.zip"
                            
                            val source = when {
                                CommandExecutor.executeWithSu("[ -f \"$prioritizedBackup\" ] && echo yes").contains("yes") -> prioritizedBackup
                                else -> {
                                    val firstFile = CommandExecutor.executeWithSu("ls \"$originalDir\" | head -n 1").trim()
                                    if (firstFile.isNotEmpty() && !firstFile.startsWith("Error:")) {
                                        "$originalDir/$firstFile"
                                    } else {
                                        systemPath
                                    }
                                }
                            }
                            CommandExecutor.executeWithSu("cp \"$source\" \"${target.absolutePath}\" && chmod 644 \"${target.absolutePath}\"")
                        }
                    }
                    
                    val previewFile = File(previewDir, "original_${systemPath.trimStart('/').replace('/', '_')}.mp4")
                    initialAnims.add(com.bootstudio.ui.screens.BootAnimation(
                        name = "System Animation",
                        path = systemPath,
                        isAsset = false,
                        tag = "System",
                        previewUri = if (previewFile.exists()) Uri.fromFile(previewFile) else null
                    ))
                }

                // 2. Built-in
                zipFiles.forEach { fileName ->
                    initialAnims.add(com.bootstudio.ui.screens.BootAnimation(
                        name = fileName.removePrefix("bootanimation_").removeSuffix(".zip"),
                        path = "bootanimations/$fileName",
                        isAsset = true,
                        tag = "Built-in"
                    ))
                }

                // 3. Library
                val libraryDir = File(context.filesDir, "library")
                if (libraryDir.exists()) {
                    val metaPrefs = context.getSharedPreferences("anim_metadata", android.content.Context.MODE_PRIVATE)
                    libraryDir.listFiles()?.filter { it.extension == "zip" }?.forEach { file ->
                        initialAnims.add(com.bootstudio.ui.screens.BootAnimation(
                            name = file.nameWithoutExtension,
                            path = file.absolutePath,
                            isAsset = false,
                            tag = metaPrefs.getString("${file.name}_tag", "Created") ?: "Created",
                            creator = metaPrefs.getString("${file.name}_creator", null)
                        ))
                    }
                }

                withContext(Dispatchers.Main) {
                    animations.clear()
                    animations.addAll(initialAnims)
                    isLoading = false
                }
            } catch (e: Exception) {
                isLoading = false
            }
        }
    }

    LaunchedEffect(currentPaths, refreshTrigger) {
        loadAnimations()
    }

    // Automatically refresh when a download finishes in DownloadService
    val downloadingItems by com.bootstudio.service.DownloadService.downloadingItems.collectAsState()
    var lastDownloadingCount by remember { mutableIntStateOf(0) }
    
    LaunchedEffect(downloadingItems.size) {
        if (downloadingItems.size < lastDownloadingCount) {
            loadAnimations()
        }
        lastDownloadingCount = downloadingItems.size
    }
    
    var selectedItem by remember { mutableIntStateOf(0) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    if (selectedItem != 0 && previewPath == null && !showSettings) {
        BackHandler {
            selectedItem = 0
        }
    }

    val items = listOf("Home", "Create", "Community")
    val icons = listOf(Icons.Default.Home, Icons.Default.Add, Icons.Default.Person)

    if (previewPath != null) {
        PreviewScreen(zipPath = previewPath!!, onBack = { previewPath = null })
    } else if (showSettings) {
        SettingsScreen(
            currentPaths = currentPaths,
            onPathsChange = onPathsChange,
            onRefreshRequest = onRefreshRequest,
            onBack = { showSettings = false }
        )
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    items.forEachIndexed { index, item ->
                        NavigationBarItem(
                            icon = { Icon(icons[index], contentDescription = item) },
                            label = { Text(item) },
                            selected = selectedItem == index,
                            onClick = { selectedItem = index }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Surface(
                modifier = Modifier.padding(innerPadding),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (selectedItem) {
                        0 -> HomeScreen(
                            currentPaths = currentPaths,
                            animations = animations,
                            isLoading = isLoading,
                            refreshTrigger = refreshTrigger,
                            onRefreshRequest = onRefreshRequest,
                            onPreview = { previewPath = it },
                            onSettings = { showSettings = true }
                        )
                        1 -> CreateScreen(onSuccess = { 
                            onRefreshRequest()
                            selectedItem = 0 
                        })
                        2 -> CommunityScreen()
                    }
                }
            }
        }
    }
}
