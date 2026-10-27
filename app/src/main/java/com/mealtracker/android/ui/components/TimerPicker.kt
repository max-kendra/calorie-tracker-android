package com.mealtracker.android.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val PICKER_MINUTES = listOf(
    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 75, 90, 105, 120, 150, 180
)

/** "45 s", "5 min", "1 h 30 min". Timers are stored in seconds but shown
 * and picked in minutes. */
fun formatTimer(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val parts = buildList {
        if (hours > 0) add("$hours h")
        if (minutes > 0) add("$minutes min")
        if (seconds > 0) add("$seconds s")
    }
    return if (parts.isEmpty()) "0 s" else parts.joinToString(" ")
}

/**
 * Dropdown picker for a step timer. [seconds] is null for "no timer". A
 * clickable Box with a plain DropdownMenu, not ExposedDropdownMenuBox, for
 * the same reason as the new-trip store picker (that component has failed
 * to compile on this project's Compose version). A current value that
 * isn't one of the presets (e.g. an older step saved as 150s) is added to
 * the list so opening a step never silently rounds it.
 */
@Composable
fun TimerPicker(
    seconds: Int?,
    onChange: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val presets = PICKER_MINUTES.map { it * 60 }
    val options = if (seconds != null && seconds > 0 && seconds !in presets) (presets + seconds).sorted() else presets

    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text("Timer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (seconds == null) "No timer" else formatTimer(seconds),
                style = MaterialTheme.typography.bodyLarge
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp)
        ) {
            DropdownMenuItem(text = { Text("No timer") }, onClick = { onChange(null); expanded = false })
            options.forEach { option ->
                DropdownMenuItem(text = { Text(formatTimer(option)) }, onClick = { onChange(option); expanded = false })
            }
        }
    }
}
