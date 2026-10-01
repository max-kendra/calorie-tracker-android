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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.graphics.Color
import com.mealtracker.android.ui.components.DonutChart
import com.mealtracker.android.ui.components.PageIndicator

/**
 * Paged create/edit flow for one goal (see design discussion and
 * GoalEditFlowViewModel's own doc comment for the fuller reasoning).
 * goalId null creates a new goal; a real id edits an existing one.
 *
 * The ViewModel takes a constructor parameter (goalId), unlike every
 * other ViewModel in this codebase (all plain no-arg + viewModel()) -
 * needs a real ViewModelProvider.Factory rather than the usual
 * default, built inline here since nothing else needs to share it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalEditFlowScreen(
    goalId: Int?,
    onDone: () -> Unit,
    viewModel: GoalEditFlowViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GoalEditFlowViewModel(goalId) as T
        }
    })
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (goalId == null) "New goal" else "Edit goal") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.loadError != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.loadError!!, color = MaterialTheme.colorScheme.error)
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    PageIndicator(pageCount = 3, currentPage = state.page)

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                    ) {
                        when (state.page) {
                            0 -> DatesAndCaloriesPage(state, viewModel)
                            1 -> MacroRatioPage(state, viewModel)
                            else -> MealSplitPage(state, viewModel)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (state.page > 0) {
                            OutlinedButton(onClick = { viewModel.goToPage(state.page - 1) }) { Text("Back") }
                        } else {
                            Spacer(modifier = Modifier.height(1.dp))
                        }

                        if (state.page < 2) {
                            Button(
                                onClick = { viewModel.goToPage(state.page + 1) },
                                enabled = state.kcalTarget != null && state.ratioValid
                            ) { Text("Next") }
                        } else {
                            Button(
                                onClick = { viewModel.save(onDone) },
                                enabled = !state.isSaving && state.kcalTarget != null && state.ratioValid && state.mealSplitValid
                            ) { Text(if (state.isSaving) "Saving..." else if (goalId == null) "Create" else "Save") }
                        }
                    }
                    if (state.saveError != null) {
                        Text(
                            state.saveError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DatesAndCaloriesPage(state: GoalEditFlowUiState, viewModel: GoalEditFlowViewModel) {
    Text("Dates & calories", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
    OutlinedTextField(
        value = state.startDate,
        onValueChange = { viewModel.updateStartDate(it) },
        label = { Text("Start date (YYYY-MM-DD)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Ongoing (no end date yet)")
        androidx.compose.material3.Switch(checked = state.isOngoing, onCheckedChange = { viewModel.updateIsOngoing(it) })
    }
    if (!state.isOngoing) {
        OutlinedTextField(
            value = state.endDate,
            onValueChange = { viewModel.updateEndDate(it) },
            label = { Text("End date (YYYY-MM-DD)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
    OutlinedTextField(
        value = state.kcalTargetInput,
        onValueChange = { viewModel.updateKcalTargetInput(it) },
        label = { Text("Calories") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(24.dp))
}

@Composable
private fun MacroRatioPage(state: GoalEditFlowUiState, viewModel: GoalEditFlowViewModel) {
    Text("Macro ratio", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))

    // Same DonutChart component MacronutrientsScreen already uses -
    // genuinely reusable (just fraction+color pairs, no ViewModel
    // coupling at all), and the same exact hex colors (also matching
    // web's own MACRO_COLORS) - see design discussion.
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        DonutChart(
            segments = listOf(
                state.fatPct / 100f to GoalFlowFatColor,
                state.carbsPct / 100f to GoalFlowCarbsColor,
                state.fiberPct / 100f to GoalFlowFiberColor,
                state.proteinPct / 100f to GoalFlowProteinColor
            ),
            centerContent = {
                Text(
                    "${state.ratioTotal}%",
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (state.ratioValid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                )
            }
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    if (!state.ratioValid) {
        Text(
            "Must total 100%",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }

    MacroStepperRow("Protein", state.proteinPct, state.proteinGrams, GoalFlowProteinColor, { viewModel.adjustProteinPct(-1) }, { viewModel.adjustProteinPct(1) })
    MacroStepperRow("Carbs", state.carbsPct, state.carbsGrams, GoalFlowCarbsColor, { viewModel.adjustCarbsPct(-1) }, { viewModel.adjustCarbsPct(1) })
    MacroStepperRow("Fat", state.fatPct, state.fatGrams, GoalFlowFatColor, { viewModel.adjustFatPct(-1) }, { viewModel.adjustFatPct(1) })
    MacroStepperRow("Fiber", state.fiberPct, state.fiberGrams, GoalFlowFiberColor, { viewModel.adjustFiberPct(-1) }, { viewModel.adjustFiberPct(1) })
    Spacer(modifier = Modifier.height(24.dp))
}

@Composable
private fun MacroStepperRow(label: String, pct: Int, grams: Double, color: Color, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = color, modifier = Modifier.weight(1f))
        IconButton(onClick = onDecrease) { Text("\u2212") }
        Text(
            "$pct% - ${grams.toInt()}g",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        IconButton(onClick = onIncrease) { Text("+") }
    }
}

@Composable
private fun MealSplitPage(state: GoalEditFlowUiState, viewModel: GoalEditFlowViewModel) {
    Text("Meal split", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        DonutChart(
            segments = listOf(
                (state.breakfastPctInput.toFloatOrNull() ?: 0f) / 100f to GoalFlowBreakfastColor,
                (state.lunchPctInput.toFloatOrNull() ?: 0f) / 100f to GoalFlowLunchColor,
                (state.dinnerPctInput.toFloatOrNull() ?: 0f) / 100f to GoalFlowDinnerColor,
                (state.snackPctInput.toFloatOrNull() ?: 0f) / 100f to GoalFlowSnackColor
            ),
            centerContent = {
                Text(
                    "${state.mealSplitTotal.toInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (state.mealSplitValid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                )
            }
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    if (!state.mealSplitValid) {
        Text(
            "Must total 100%",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }

    MealSplitField("Breakfast", state.breakfastPctInput, GoalFlowBreakfastColor) { viewModel.updateBreakfastPctInput(it) }
    MealSplitField("Lunch", state.lunchPctInput, GoalFlowLunchColor) { viewModel.updateLunchPctInput(it) }
    MealSplitField("Dinner", state.dinnerPctInput, GoalFlowDinnerColor) { viewModel.updateDinnerPctInput(it) }
    MealSplitField("Snack", state.snackPctInput, GoalFlowSnackColor) { viewModel.updateSnackPctInput(it) }
    Spacer(modifier = Modifier.height(24.dp))
}

@Composable
private fun MealSplitField(label: String, value: String, color: Color, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("$label (%)", color = color) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

// Same exact hex values as MacronutrientsScreen/MealCalorieGoalScreen
// (both private to their own files, so re-declared here rather than
// imported) - and the macro ones match web's own MACRO_COLORS too.
private val GoalFlowFatColor = Color(0xFFE6B800)
private val GoalFlowProteinColor = Color(0xFFE8837A)
private val GoalFlowCarbsColor = Color(0xFF7EC8E3)
private val GoalFlowFiberColor = Color(0xFF9C7A54)
private val GoalFlowBreakfastColor = Color(0xFF2A9D8F)
private val GoalFlowLunchColor = Color(0xFFE76F51)
private val GoalFlowDinnerColor = Color(0xFF8AB17D)
private val GoalFlowSnackColor = Color(0xFFE9C46A)