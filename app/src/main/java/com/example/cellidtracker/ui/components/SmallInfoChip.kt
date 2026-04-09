package com.example.cellidtracker.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun SmallInfoChip(label: String, value: String) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text("$label $value") },
        modifier = Modifier.alpha(if (value.isBlank()) 0.5f else 1f)
    )
}
