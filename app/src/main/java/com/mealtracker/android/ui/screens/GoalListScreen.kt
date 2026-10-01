package com.mealtracker.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mealtracker.android.network.models.Goal

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GoalListScreen(
    onBack: () -> Unit,
    onEditGoal: (Int) -> Unit,
    onNewGoal: () -> Unit,
    viewModel: GoalListViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var pendingDeleteGoal by remember { mutableStateOf<Goal?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onNewGoal) {
                Icon(Icons.Filled.Add, contentDescription = "New goal")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Goals", style = MaterialTheme.typography.headlineSmall)
            }
            when {
                state.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.loadError != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(state.loadError!!, color = MaterialTheme.colorScheme.error)
                    }
                }
                else -> {
                    val current = state.goals.filter { goalStatus(it) == GoalStatus.CURRENT }
                    val upcoming = state.goals.filter { goalStatus(it) == GoalStatus.UPCOMING }.sortedBy { it.startDate }
                    val past = state.goals.filter { goalStatus(it) == GoalStatus.PAST }.sortedByDescending { it.startDate }

                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        item { Spacer(modifier = Modifier.height(8.dp)) }
                        if (current.isNotEmpty()) {
                            item { GoalGroupHeader("Current") }
                            items(current, key = { it.id }) { goal ->
                                GoalRow(goal = goal, onClick = { onEditGoal(goal.id) }, onDeleteClick = { pendingDeleteGoal = goal })
                            }
                        }
                        if (upcoming.isNotEmpty()) {
                            item { GoalGroupHeader("Upcoming") }
                            items(upcoming, key = { it.id }) { goal ->
                                GoalRow(goal = goal, onClick = { onEditGoal(goal.id) }, onDeleteClick = { pendingDeleteGoal = goal })
                            }
                        }
                        if (past.isNotEmpty()) {
                            item { GoalGroupHeader("Past") }
                            items(past, key = { it.id }) { goal ->
                                GoalRow(goal = goal, onClick = { onEditGoal(goal.id) }, onDeleteClick = { pendingDeleteGoal = goal })
                            }
                        }
                        if (state.goals.isEmpty()) {
                            item {
                                Text(
                                    "No goals yet - tap + to create one",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 24.dp)
                                )
                            }
                        }
                        item { Spacer(modifier = Modifier.height(72.dp)) } // room for the FAB
                    }
                }
            }
            }
        }
    }

    if (pendingDeleteGoal != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteGoal = null },
            title = { Text("Delete this goal?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteGoal(pendingDeleteGoal!!.id)
                    pendingDeleteGoal = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteGoal = null }) { Text("Cancel") }
            }
        )
    }

    if (state.actionError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissActionError() },
            confirmButton = { TextButton(onClick = { viewModel.dismissActionError() }) { Text("OK") } },
            text = { Text(state.actionError!!) }
        )
    }
}

@Composable
private fun GoalGroupHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun GoalRow(goal: Goal, onClick: () -> Unit, onDeleteClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text("${goal.startDate} - ${goal.endDate ?: "Ongoing"}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${goal.kcalTarget} Cal",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDeleteClick) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete goal")
            }
        }
    }
}