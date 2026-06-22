package com.bootstudio.ui.screens

import android.media.MediaMetadataRetriever
import android.net.Uri
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen() {
    val context = LocalContext.current
    
    // State for inputs
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var fps by remember { mutableStateOf("60") }
    var width by remember { mutableStateOf("1080") }
    var height by remember { mutableStateOf("2400") }

    var isAdvanced by remember { mutableStateOf(false) }
    var parts by remember { mutableStateOf(listOf(AnimationPartState())) }
    var activePartIndex by remember { mutableStateOf(-1) }

    var showSourceDialog by remember { mutableStateOf(false) }

    val handleFileSelection: (Uri?) -> Unit = { uri ->
        if (isAdvanced && activePartIndex >= 0) {
            parts = parts.toMutableList().also { 
                it[activePartIndex] = it[activePartIndex].copy(uri = uri) 
            }
        } else {
            selectedUri = uri
        }
        
        uri?.let {
            if (!isAdvanced) fileName = it.path?.split("/")?.last() ?: "Selected File"
            
            // Auto-detect metadata
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, it)
                
                val vWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val vHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                
                val vFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)

                if (vWidth != null) width = vWidth
                if (vHeight != null) height = vHeight
                if (vFps != null) fps = vFps
                
                retriever.release()
            } catch (e: Exception) {
                e.printStackTrace()
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
                    /*
                    if (selectedUri == null) {
                        Text(
                            text = "Video",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                    }
                    */

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
                    onCheckedChange = { isAdvanced = it },
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
                        parts = parts.toMutableList().also { it.add(AnimationPartState(folder = "part${it.size}")) } 
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
                            showSourceDialog = true
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

        // 3. Generate Button
        Button(
            onClick = { /* Generate Logic */ },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            enabled = selectedUri != null
        ) {
            Text(
                text = "Generate Boot Animation",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SequenceLine(
    index: Int,
    part: AnimationPartState,
    onUpdate: (AnimationPartState) -> Unit,
    onPickMedia: () -> Unit
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
                    label = { Text("Type", fontSize = 8.sp) },
                    /*
                    trailingIcon = { 
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                     */
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
                    .height(54.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f))
                    .clickable { onPickMedia() }
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Add, 
                        contentDescription = "Pick Media", 
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    /*
                    Text(
                        text = "Video",
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        fontWeight = FontWeight.Bold
                    )
                    */
                    Text(
                        text = part.uri?.path?.split("/")?.last() ?: "Select",
                        fontSize = 7.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

data class AnimationPartState(
    val type: String = "c",
    val repeats: String = "1",
    val delay: String = "0",
    val folder: String = "part0",
    val uri: Uri? = null
)
