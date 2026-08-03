package com.bootstudio

import android.content.Intent
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

    private var importTrigger = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLogger.init(this)
        BootAnimParser.cleanCache(this)
        com.arthenica.ffmpegkit.FFmpegKit.init(this)

        handleIntent(intent)

        val prefs = getSharedPreferences("bootstudio_prefs", MODE_PRIVATE)
        val initialPath = prefs.getString("boot_anim_path", null)

        enableEdgeToEdge()
        setContent {
            val scope = rememberCoroutineScope()
            val refreshCount by remember { importTrigger }
            BootStudioTheme {
                var currentPath by remember { mutableStateOf(initialPath) }
                var hasRoot by remember { mutableStateOf<Boolean?>(null) }

                LaunchedEffect(currentPath) {
                    if (currentPath != null) {
                        // Check for root if setup is already finished
                        withContext(Dispatchers.IO) {
                            val success = CommandExecutor.initRootSession()
                            hasRoot = success
                        }
                    }
                }

                if (currentPath == null) {
                    SetupScreen(onSetupComplete = { path, allPaths ->
                        // Initial root check happens inside createMagiskModule or here
                        scope.launch {
                            val success = withContext(Dispatchers.IO) {
                                CommandExecutor.initRootSession()
                            }
                            if (success) {
                                withContext(Dispatchers.IO) {
                                    ModuleManager.createModule(path)
                                }
                                prefs.edit()
                                    .putString("boot_anim_path", path)
                                    .putStringSet("all_boot_anim_paths", allPaths.toSet())
                                    .apply()
                                currentPath = path
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
                        currentPath = currentPath ?: "",
                        refreshTrigger = refreshCount,
                        onPathChange = { newPath ->
                            val oldPath = currentPath
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    // 1. Revert the old path so it's clean in Magisk
                                    if (oldPath != null) {
                                        ModuleManager.setDefaultAnimation(oldPath)
                                    }

                                    // 2. Clear cache for system animation to force re-extraction
                                    val cachedFile = File(cacheDir, "system_backup.zip")
                                    if (cachedFile.exists()) cachedFile.delete()

                                    // 3. Setup the new path
                                    ModuleManager.createModule(newPath)

                                    // 4. Clear the preview MP4 for the new system path to force recreation
                                    val previewDir = File(cacheDir, "previews")
                                    val backupFileName = newPath.trimStart('/').replace('/', '_')
                                    val previewFile = File(previewDir, "original_${backupFileName}.mp4")
                                    if (previewFile.exists()) previewFile.delete()
                                }

                                // 5. Reset applied animation to system default for the new path
                                prefs.edit()
                                    .putString("boot_anim_path", newPath)
                                    .putString("applied_anim_path", "system_default")
                                    .apply()

                                currentPath = newPath
                            }
                        }
                    )
                } else {
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
                        importTrigger.value++
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
fun MainScreen(currentPath: String, refreshTrigger: Int = 0, onPathChange: (String) -> Unit) {
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
            currentPath = currentPath,
            onPathChange = onPathChange,
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
                            currentPath = currentPath,
                            refreshTrigger = refreshTrigger,
                            onPreview = { previewPath = it },
                            onSettings = { showSettings = true }
                        )
                        1 -> CreateScreen(onSuccess = { selectedItem = 0 })
                        2 -> CommunityScreen()
                    }
                }
            }
        }
    }
}

// su -c 'find / -path /data/media -prune -o -path /storage -prune -o -path /mnt -prune -o -name "bootanimation.zip" -print 2>/dev/null'