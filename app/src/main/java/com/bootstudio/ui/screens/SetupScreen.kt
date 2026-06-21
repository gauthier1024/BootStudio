package com.bootstudio.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import utils.CommandExecutor
import rikka.shizuku.Shizuku

enum class SetupStep {
    GRANT_PERMISSION,
    SEARCHING,
    SELECT_PATH,
    DONE
}

data class ConsoleLine(val text: String, val isFound: Boolean = false)

@Composable
fun SetupScreen(onSetupComplete: (String) -> Unit) {
    val context = LocalContext.current
    var currentStep by remember { mutableStateOf(SetupStep.GRANT_PERMISSION) }
    var statusMessage by remember { mutableStateOf("Welcome to BootStudio") }
    var foundPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var consoleLines by remember { mutableStateOf<List<ConsoleLine>>(emptyList()) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var permissionMethod by remember { mutableStateOf<String?>(null) } // "su" or "shizuku"
    
    val scope = rememberCoroutineScope()
    
    // Command that prints directories as it traverses, and specific files
    // -type d -print prints directories
    // -name "bootanimation.zip" -print prints the target
    val searchCommand = "find / -path /data/media -prune -o -path /storage -prune -o -path /mnt -prune -o -path /proc -prune -o -type d -print -o -name \"bootanimation.zip\" -print 2>/dev/null"

    fun autoSelectPath(paths: List<String>) {
        val priority = listOf(
            "/data/misc/bootanim/bootanimation.zip",
            "/system/media/bootanimation.zip",
            "/product/media/bootanimation.zip",
            "/system/product/media/bootanimation.zip"
        )
        selectedPath = priority.firstOrNull { paths.contains(it) } ?: paths.firstOrNull()
    }

    fun startSearch() {
        currentStep = SetupStep.SEARCHING
        statusMessage = "Scanning system directories..."
        consoleLines = emptyList()
        val tempFoundPaths = mutableListOf<String>()
        
        scope.launch {
            // Buffer to hold incoming lines before they are batched to the UI
            val updateChannel = Channel<ConsoleLine>(capacity = 200)

            // UI Updater Coroutine: Batches updates to avoid overwhelming the main thread
            val updaterJob = scope.launch {
                var lastUpdateTime = 0L
                val batch = mutableListOf<ConsoleLine>()
                
                updateChannel.consumeAsFlow().collect { line ->
                    batch.add(line)
                    val currentTime = System.currentTimeMillis()
                    
                    // Update UI at most every 50ms OR if it's a "found" file
                    if (currentTime - lastUpdateTime > 50 || line.isFound) {
                        consoleLines = (consoleLines + batch).takeLast(10) // Keep only last 10 lines
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
                    // Try to send to channel, ignore if it's full (dropping frames is better than crashing)
                    updateChannel.trySend(ConsoleLine(line, isFound)).getOrNull()
                }

                if (permissionMethod == "su") {
                    CommandExecutor.executeWithSu(searchCommand, callback)
                } else {
                    CommandExecutor.executeWithShizuku(searchCommand, callback)
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
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "To customize your boot animation, BootStudio needs elevated permissions.",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { 
                                    permissionMethod = "su"
                                    startSearch()
                                }) {
                                    Text("Use Root (su)")
                                }
                                Button(onClick = { 
                                    try {
                                        if (!Shizuku.pingBinder()) {
                                            statusMessage = "Shizuku is not running. Launching app..."
                                            val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                            if (intent != null) {
                                                context.startActivity(intent)
                                            } else {
                                                statusMessage = "Shizuku app not found. Please install it."
                                            }
                                        } else if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                            Shizuku.requestPermission(1001)
                                            statusMessage = "Permission requested in Shizuku."
                                        } else {
                                            permissionMethod = "shizuku"
                                            startSearch()
                                        }
                                    } catch (e: Exception) {
                                        statusMessage = "Shizuku Error: ${e.message}"
                                    }
                                }) {
                                    Text("Use Shizuku")
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            if (statusMessage != "Welcome to BootStudio") {
                                Text(statusMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                            }
                        }
                    }

                    SetupStep.SEARCHING -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator(modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = statusMessage, 
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // Tiny Console
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(8.dp)
                            ) {
                                val listState = rememberLazyListState()
                                LaunchedEffect(consoleLines.size) {
                                    if (consoleLines.isNotEmpty()) {
                                        listState.animateScrollToItem(consoleLines.size - 1)
                                    }
                                }
                                
                                LazyColumn(state = listState) {
                                    items(consoleLines) { line ->
                                        Text(
                                            text = line.text,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Normal,
                                            modifier = Modifier.padding(vertical = 1.dp)
                                        )
                                    }
                                    if (consoleLines.isEmpty()) {
                                        item {
                                            Text(
                                                text = "> Initializing scan...",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    SetupStep.SELECT_PATH -> {
                        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Multiple files found.", 
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                "Please select the active boot animation for your ROM:", 
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Box(modifier = Modifier.weight(1f, fill = false).maxHeight(300.dp).fillMaxWidth()) {
                                LazyColumn {
                                    items(foundPaths) { path ->
                                        Surface(
                                            onClick = { selectedPath = path },
                                            shape = MaterialTheme.shapes.medium,
                                            color = if (path == selectedPath) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(selected = (path == selectedPath), onClick = null)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = path, 
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (path == selectedPath) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { currentStep = SetupStep.DONE },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = selectedPath != null
                            ) {
                                Text("Continue")
                            }
                        }
                    }

                    SetupStep.DONE -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.CheckCircle, 
                                contentDescription = null, 
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Setup Complete!", 
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Active path: $selectedPath", 
                                textAlign = TextAlign.Center, 
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(onClick = { 
                                selectedPath?.let { onSetupComplete(it) }
                            }) {
                                Text("Go to Dashboard")
                            }
                        }
                    }
                }
            }
        }
    }
}

fun Modifier.maxHeight(height: androidx.compose.ui.unit.Dp) = this.heightIn(max = height)
