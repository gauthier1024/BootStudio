package com.bootstudio.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bootstudio.ui.screens.setup.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import utils.CommandExecutor
import utils.FFmpegDownloader

@Composable
fun SetupScreen(onSetupComplete: (String) -> Unit) {
    val context = LocalContext.current
    var currentStep by remember { mutableStateOf(SetupStep.GRANT_PERMISSION) }
    var statusMessage by remember { mutableStateOf("Welcome to BootStudio") }
    var foundPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var consoleLines by remember { mutableStateOf<List<ConsoleLine>>(emptyList()) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var permissionMethod by remember { mutableStateOf<String?>(null) } // "su" or "shizuku"
    var downloadProgress by remember { mutableStateOf(0f) }
    var lastErrorMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    val searchCommand = "find / -path /data/media -prune -o -path /storage -prune -o -path /mnt -prune -o -path /proc -prune -o -path /data/adb/modules/BootStudio -prune -o -type d -print -o -name \"bootanimation.zip\" -print 2>/dev/null"

    fun autoSelectPath(paths: List<String>) {
        val priority = listOf(
            "/data/misc/bootanim/bootanimation.zip",
            "/system/media/bootanimation.zip",
            "/product/media/bootanimation.zip",
            "/system/product/media/bootanimation.zip"
        )
        selectedPath = priority.firstOrNull { paths.contains(it) } ?: paths.firstOrNull()
    }

    fun startDownload() {
        currentStep = SetupStep.DOWNLOAD_FFMPEG
        statusMessage = "Connecting to download server..."
        lastErrorMessage = null
        scope.launch {
            val success = FFmpegDownloader.downloadAndInstall(context) { progress, error ->
                if (error != null) {
                    lastErrorMessage = error
                } else {
                    statusMessage = "Downloading FFmpeg binaries..."
                    downloadProgress = progress
                }
            }
            if (success) {
                statusMessage = "Libraries downloaded."
                delay(500)
                currentStep = SetupStep.DONE
            } else {
                statusMessage = lastErrorMessage ?: "Download failed. Please check your connection."
                currentStep = SetupStep.GRANT_PERMISSION
            }
        }
    }

    fun startSearch() {
        currentStep = SetupStep.SEARCHING
        statusMessage = "Scanning system directories..."
        consoleLines = emptyList()
        val tempFoundPaths = mutableListOf<String>()

        scope.launch {
            val updateChannel = Channel<ConsoleLine>(capacity = 200)

            val updaterJob = scope.launch {
                var lastUpdateTime = 0L
                val batch = mutableListOf<ConsoleLine>()

                updateChannel.consumeAsFlow().collect { line ->
                    batch.add(line)
                    val currentTime = System.currentTimeMillis()

                    if (currentTime - lastUpdateTime > 50 || line.isFound) {
                        consoleLines = (consoleLines + batch).takeLast(10)
                        batch.clear()
                        lastUpdateTime = currentTime
                    }
                }
            }

            val result = withContext(Dispatchers.IO) {
                val callback: (String) -> Unit = { line ->
                    val isFound = line.endsWith("bootanimation.zip")
                    if (isFound) {
                        tempFoundPaths.add(line)
                    }
                    updateChannel.trySend(ConsoleLine(line, isFound)).getOrNull()
                }

                if (permissionMethod == "su") {
                    CommandExecutor.executeWithSu(searchCommand, purpose = "find bootanimation.zip", onLine = callback)
                } else {
                    CommandExecutor.executeWithShizuku(searchCommand, purpose = "find bootanimation.zip", onLine = callback)
                }
            }

            updateChannel.close()
            updaterJob.join()

            if (result.startsWith("su Error") || result.startsWith("Shizuku Error") ||
                result.startsWith("Could not execute") || result == "Shizuku not authorized") {
                statusMessage = result
                currentStep = SetupStep.GRANT_PERMISSION
            } else {
                if (tempFoundPaths.isEmpty()) {
                    statusMessage = "No bootanimation.zip found. Ensure your device is compatible."
                    currentStep = SetupStep.GRANT_PERMISSION
                } else {
                    foundPaths = tempFoundPaths
                    autoSelectPath(tempFoundPaths)
                    currentStep = SetupStep.SELECT_PATH
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Setup BootStudio",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(32.dp))

            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    fadeIn() + scaleIn(initialScale = 0.92f) togetherWith fadeOut() + scaleOut(targetScale = 0.92f)
                },
                label = "setup_step_transition"
            ) { step ->
                when (step) {
                    SetupStep.GRANT_PERMISSION -> {
                        PermissionStep(
                            statusMessage = statusMessage,
                            onSuClick = {
                                permissionMethod = "su"
                                startSearch()
                            },
                            onShizukuClick = {
                                statusMessage = "Shizuku support is currently in development."
                            }
                        )
                    }

                    SetupStep.SEARCHING -> {
                        SearchingStep(
                            statusMessage = statusMessage,
                            consoleLines = consoleLines
                        )
                    }

                    SetupStep.SELECT_PATH -> {
                        SelectPathStep(
                            foundPaths = foundPaths,
                            selectedPath = selectedPath,
                            onPathSelect = { selectedPath = it },
                            onContinueClick = {
                                if (!FFmpegDownloader.isInstalled(context)) {
                                    currentStep = SetupStep.ASK_DOWNLOAD_FFMPEG
                                } else {
                                    currentStep = SetupStep.DONE
                                }
                            }
                        )
                    }

                    SetupStep.ASK_DOWNLOAD_FFMPEG -> {
                        AskDownloadStep(
                            onDownloadClick = { startDownload() }
                        )
                    }

                    SetupStep.DOWNLOAD_FFMPEG -> {
                        DownloadProgressStep(
                            downloadProgress = downloadProgress,
                            statusMessage = statusMessage
                        )
                    }

                    SetupStep.DONE -> {
                        DoneStep(
                            selectedPath = selectedPath,
                            onFinishClick = {
                                selectedPath?.let { onSetupComplete(it) }
                            }
                        )
                    }
                }
            }
        }
    }
}
