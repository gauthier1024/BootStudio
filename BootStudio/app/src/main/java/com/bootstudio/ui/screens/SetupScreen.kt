package com.bootstudio.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import com.bootstudio.ui.screens.setup.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import utils.CommandExecutor
import utils.RootType

@Composable
fun SetupScreen(onSetupComplete: (String, List<String>) -> Unit) {
    val context = LocalContext.current
    var currentStep by remember { mutableStateOf(SetupStep.GRANT_PERMISSION) }
    var statusMessage by remember { mutableStateOf("Welcome to BootStudio") }
    var foundPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var consoleLines by remember { mutableStateOf<List<ConsoleLine>>(emptyList()) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    
    var detectedRoot by remember { mutableStateOf(RootType.UNKNOWN) }
    var selectedRoot by remember { mutableStateOf(RootType.UNKNOWN) }
    var hybridMountFolder by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(currentStep) {
        if (currentStep == SetupStep.DETECT_ROOT) {
            while (true) {
                hybridMountFolder = withContext(Dispatchers.IO) { CommandExecutor.getHybridMountFolderName() }
                delay(2000)
            }
        }
    }

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

    fun startDetection() {
        scope.launch {
            val isRoot = withContext(Dispatchers.IO) { CommandExecutor.initRootSession() }
            if (isRoot) {
                detectedRoot = withContext(Dispatchers.IO) { CommandExecutor.detectRootSystem() }
                hybridMountFolder = withContext(Dispatchers.IO) { CommandExecutor.getHybridMountFolderName() }
                selectedRoot = detectedRoot
                currentStep = SetupStep.DETECT_ROOT
            } else {
                statusMessage = "Root access denied. Please grant permission in your root manager."
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

                CommandExecutor.executeWithSu(searchCommand, purpose = "setup", onLine = callback)
            }

            updateChannel.close()
            updaterJob.join()

            if (result.startsWith("su Error") || result.startsWith("Could not execute")) {
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

    fun handleBack() {
        val steps = SetupStep.entries
        val currentIndex = currentStep.ordinal
        if (currentIndex > 0) {
            currentStep = steps[currentIndex - 1]
        }
    }

    BackHandler(enabled = currentStep != SetupStep.GRANT_PERMISSION) {
        handleBack()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp) // Adjusted top padding
        ) {
            val totalSteps = SetupStep.entries.size
            val currentStepProgress = (currentStep.ordinal + 1).toFloat() / totalSteps

            Box(modifier = Modifier.fillMaxWidth()) {
                if (currentStep != SetupStep.GRANT_PERMISSION) {
                    IconButton(
                        onClick = { handleBack() },
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "BootStudio Setup",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { currentStepProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Step ${currentStep.ordinal + 1} of $totalSteps",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(48.dp))

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = {
                        val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (fadeIn() + slideInHorizontally { direction * it / 2 })
                            .togetherWith(fadeOut() + slideOutHorizontally { -direction * it / 2 })
                    },
                    label = "setup_step_transition"
                ) { step ->
                    when (step) {
                        SetupStep.GRANT_PERMISSION -> {
                            PermissionStep(
                                statusMessage = statusMessage,
                                onSuClick = {
                                    startDetection()
                                }
                            )
                        }

                        SetupStep.DETECT_ROOT -> {
                            DetectRootStep(
                                detectedRoot = detectedRoot,
                                selectedRoot = selectedRoot,
                                hybridMountFolder = hybridMountFolder,
                                onRootSelect = { selectedRoot = it },
                                onContinueClick = {
                                    currentStep = SetupStep.READY_TO_SCAN
                                }
                            )
                        }

                        SetupStep.READY_TO_SCAN -> {
                            ReadyToScanStep(
                                onStartScan = {
                                    startSearch()
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
                                    currentStep = SetupStep.DONE
                                }
                            )
                        }

                        SetupStep.DONE -> {
                            DoneStep(
                                selectedPath = selectedPath,
                                onFinishClick = {
                                    selectedPath?.let { onSetupComplete(it, foundPaths) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
