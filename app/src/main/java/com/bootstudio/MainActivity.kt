package com.bootstudio

import android.os.Bundle
import android.widget.Toast
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import rikka.shizuku.Shizuku
import utils.FFmpegDownloader
import utils.MagiskManager

class MainActivity : ComponentActivity() {

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            // Permission granted, you can refresh the UI or take action if needed
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        FFmpegDownloader.initLoader(this)
        
        val prefs = getSharedPreferences("bootstudio_prefs", MODE_PRIVATE)
        val initialPath = prefs.getString("boot_anim_path", null)
        
        if (initialPath != null) {
            Toast.makeText(this, "Debug: setupPath = $initialPath", Toast.LENGTH_LONG).show()
        }

        enableEdgeToEdge()
        setContent {
            BootStudioTheme {
                var currentPath by remember { mutableStateOf(initialPath) }

                if (currentPath == null) {
                    SetupScreen(onSetupComplete = { path ->
                        // Initialize Magisk Module and back up original animation
                        MagiskManager.createMagiskModule(path)

                        prefs.edit().putString("boot_anim_path", path).apply()
                        currentPath = path
                    })
                } else {
                    MainScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }
}

@Composable
fun MainScreen() {
    var selectedItem by remember { mutableIntStateOf(0) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    
    val items = listOf("Home", "Create", "Community")
    val icons = listOf(Icons.Default.Home, Icons.Default.Add, Icons.Default.Person)

    if (previewPath != null) {
        PreviewScreen(zipPath = previewPath!!, onBack = { previewPath = null })
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
                        0 -> HomeScreen(onPreview = { previewPath = it })
                        1 -> CreateScreen()
                        2 -> CommunityScreen()
                    }
                }
            }
        }
    }
}

// su -c 'find / -path /data/media -prune -o -path /storage -prune -o -path /mnt -prune -o -name "bootanimation.zip" -print 2>/dev/null'