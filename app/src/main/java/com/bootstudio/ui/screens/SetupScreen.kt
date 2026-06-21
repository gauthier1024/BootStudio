package com.bootstudio.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import utils.CommandExecutor
import rikka.shizuku.Shizuku

@Composable
fun SetupScreen() {
    var output by remember { mutableStateOf("Ready") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val command = "find / -path /data/media -prune -o -path /storage -prune -o -path /mnt -prune -o -name \"bootanimation.zip\" -print 2>/dev/null"

    fun runWithSu() {
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { CommandExecutor.executeWithSu(command) }
            output = result
            loading = false
        }
    }

    fun runWithShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                output = "Shizuku is not running. Please start Shizuku Manager."
                return
            }

            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1001)
                output = "Shizuku authorization request sent..."
                return
            }
        } catch (ise: IllegalStateException) {
            output = "Shizuku not initialized — open Shizuku Manager and retry authorization."
            return
        } catch (t: Throwable) {
            output = "Shizuku Error: ${t.message}"
            return
        }

        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { CommandExecutor.executeWithShizuku(command) }
            output = result
            loading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Searching for bootanimation.zip")
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = { runWithSu() }) { Text("Use root (su)") }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { runWithShizuku() }) { Text("Use Shizuku") }
        Spacer(modifier = Modifier.height(16.dp))
        if (loading) CircularProgressIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text(output)
    }
}