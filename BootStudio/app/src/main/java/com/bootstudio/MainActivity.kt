package com.bootstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bootstudio.ui.theme.BootStudioTheme
import com.bootstudio.ui.screens.ChoiceScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BootStudioTheme {
                ChoiceScreen()
            }
        }
    }
}