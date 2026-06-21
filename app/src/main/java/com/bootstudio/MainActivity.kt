package com.bootstudio

import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bootstudio.ui.theme.BootStudioTheme
import com.bootstudio.ui.screens.HomeScreen
import com.bootstudio.ui.screens.SetupScreen
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            // Permission granted, you can refresh the UI or take action if needed
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        enableEdgeToEdge()
        setContent {
            BootStudioTheme {
                SetupScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }
}

// su -c 'find / -path /data/media -prune -o -path /storage -prune -o -path /mnt -prune -o -name "bootanimation.zip" -print 2>/dev/null'