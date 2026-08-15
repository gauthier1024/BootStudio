package com.bootstudio.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import utils.CommandExecutor
import utils.DiagnosticLogger
import utils.ModuleManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentPaths: List<String>,
    onPathsChange: (List<String>) -> Unit,
    onRefreshRequest: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("bootstudio_prefs", Context.MODE_PRIVATE) }
    
    var allPaths by remember { 
        mutableStateOf(prefs.getStringSet("all_boot_anim_paths", currentPaths.toSet())?.toList()?.sorted() ?: currentPaths) 
    }
    
    var isScanning by remember { mutableStateOf(false) }
    var isReconfiguring by remember { mutableStateOf(false) }
    var showClearLogDialog by remember { mutableStateOf(false) }

    BackHandler {
        onBack()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { targetUri ->
            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    try {
                        val logFile = DiagnosticLogger.getLogFile()
                        if (logFile != null && logFile.exists()) {
                            context.contentResolver.openOutputStream(targetUri)?.use { output ->
                                logFile.inputStream().use { input ->
                                    input.copyTo(output)
                                }
                            }
                            true
                        } else {
                            false
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }
                if (success) {
                } else {
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Module Reconfiguration Section
            Text(
                text = "Module Management",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Repair or reconfigure module:",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "Re-generates module scripts and configuration based on currently selected paths.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    Button(
                        onClick = {
                            isReconfiguring = true
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    ModuleManager.createModule(currentPaths)
                                }
                                prefs.edit().putString("applied_anim_path", "system_default").apply()
                                onRefreshRequest()
                                isReconfiguring = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isReconfiguring,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        if (isReconfiguring) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onSecondary)
                        } else {
                            Icon(Icons.Default.Build, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Reconfigure Module")
                        }
                    }
                }
            }

            // Path Selection Section
            Text(
                text = "Boot Animation Configuration",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Change bootanimation path:",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    allPaths.forEach { path ->
                        val isSelected = currentPaths.contains(path)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val newPaths = if (isSelected) {
                                        if (currentPaths.size > 1) currentPaths - path else currentPaths
                                    } else {
                                        currentPaths + path
                                    }
                                    onPathsChange(newPaths)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    val newPaths = if (isSelected) {
                                        if (currentPaths.size > 1) currentPaths - path else currentPaths
                                    } else {
                                        currentPaths + path
                                    }
                                    onPathsChange(newPaths)
                                }
                            )
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp),
                                maxLines = 1
                            )
                        }
                    }

                    if (currentPaths.size < allPaths.size && currentPaths.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = "Not all paths are selected. Custom animations might not work correctly.",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = {
                            isScanning = true
                            scope.launch {
                                val searchCommand = "find / -path /data/media -prune -o -path /storage -prune -o -path /mnt -prune -o -path /proc -prune -o -path /data/adb/modules/BootStudio -prune -o -type d -print -o -name \"*bootanimation*.zip\" -print 2>/dev/null"
                                val newFoundPaths = mutableListOf<String>()
                                
                                withContext(Dispatchers.IO) {
                                    CommandExecutor.executeWithSu(searchCommand, purpose = "scan for bootanim") { line ->
                                        if (line.contains("bootanimation", ignoreCase = true) && line.endsWith(".zip", ignoreCase = true)) {
                                            newFoundPaths.add(line)
                                        }
                                    }
                                }
                                
                                if (newFoundPaths.isNotEmpty()) {
                                    allPaths = newFoundPaths.sorted()
                                    prefs.edit().putStringSet("all_boot_anim_paths", newFoundPaths.toSet()).apply()
                                } else {
                                }
                                isScanning = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isScanning
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scan for bootanimations")
                        }
                    }
                }
            }

            Text(
                text = "Diagnostic Tools",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                onClick = {
                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    exportLauncher.launch("log_$timeStamp.txt")
                }
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Column {
                        Text(text = "Export Logs", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        Text(text = "Save log.txt to Downloads for debugging", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                onClick = { showClearLogDialog = true }
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Column {
                        Text(
                            text = "Clear Logs",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Permanently delete all logs from the device",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            if (showClearLogDialog) {
                AlertDialog(
                    onDismissRequest = { showClearLogDialog = false },
                    title = { Text("Clear Logs?") },
                    text = { Text("This will permanently delete the current log file.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                DiagnosticLogger.clearLog()
                                showClearLogDialog = false
                            }
                        ) {
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearLogDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}
