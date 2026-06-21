package com.bootstudio.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun HomeScreen() {
    var selectedOption by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (selectedOption == null) {
            Button(
                onClick = { selectedOption = "Shizuku" },
                modifier = Modifier.padding(8.dp)
            ) {
                Text("Use Shizuku")
            }
            Button(
                onClick = { selectedOption = "Root" },
                modifier = Modifier.padding(8.dp)
            ) {
                Text("Use Root Access")
            }
        } else {
            Text("Selected option: $selectedOption")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    HomeScreen()
}