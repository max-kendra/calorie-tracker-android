package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.GoalCreateRequest
import com.mealtracker.android.network.models.GoalUpdateRequest
import com.mealtracker.android.network.models.MealGoalSplitRequest
import com.mealtracker.android.network.models.MealGoalSplitsUpdateRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Same factors as MacronutrientsViewModel - duplicated rather than
// shared, since that ViewModel is hard-bound to "the active goal only"
// and this flow needs to operate on an arbitrary goal (existing or
// being newly created) - see design discussion on why this is a new,
// separate flow rather than a refactor of the three existing screens.
private const val KCAL_PER_G_PROTEIN = 4.0
private const val KCAL_PER_G_CARBS = 4.0
private const val KCAL_PER_G_FAT = 9.0
private const val KCAL_PER_G_FIBER = 2.0

private val MEAL_TYPES = listOf("breakfast", "lunch", "dinner", "snack")

/** Rounds a backend-returned percentage string to a whole number,
 * matching MealCalorieGoalViewModel's own established
 * toDoubleOrNull()?.roundToInt() convention for these exact same
 * meal-split percentages - not a new rule invented for this screen
 * (see design discussion: "why would percent be decimals" - matching
 * existing, working behavior elsewhere in this app rather than my own
 * first attempt here, which wrongly preserved fractional noise like
 * "30.00" as "30" but would have kept a genuine "33.33" as-is). */
private fun roundedPctString(value: String?, fallback: String): String {
    val rounded = value?.toDoubleOrNull()?.let { kotlin.math.round(it) }?.toInt()
    return rounded?.toString() ?: fallback
}

data class GoalEditFlowUiState(
    val page: Int = 0,
    val isLoading: Boolean = false,
    val loadError: String? = null,
    val isSaving: Boolean = false,
    val saveError: String? = null,

    // Page 0 - dates + calories
    val startDate: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
    val isOngoing: Boolean = true,
    val endDate: String = "",
    val kcalTargetInput: String = "",

    // Page 1 - macro ratio (whole percent, always summing to 100 -
    // see the stepper buttons in the screen, same convention as the
    // web app's own redesign from a slider to +/- buttons)
    val proteinPct: Int = 30,
    val carbsPct: Int = 37,
    val fatPct: Int = 30,
    val fiberPct: Int = 3,

    // Page 2 - meal split (percent of daily calories per meal)
    val breakfastPctInput: String = "30",
    val lunchPctInput: String = "30",
    val dinnerPctInput: String = "30",
    val snackPctInput: String = "10"
) {
    val ratioTotal: Int get() = proteinPct + carbsPct + fatPct + fiberPct
    val ratioValid: Boolean get() = ratioTotal == 100

    val mealSplitTotal: Double
        get() = listOf(breakfastPctInput, lunchPctInput, dinnerPctInput, snackPctInput)
            .sumOf { it.toDoubleOrNull() ?: 0.0 }
    val mealSplitValid: Boolean get() = kotlin.math.abs(mealSplitTotal - 100.0) < 0.01

    val kcalTarget: Double? get() = kcalTargetInput.toDoubleOrNull()

    private fun gramsFor(pct: Int, kcalPerGram: Double): Double {
        val kcal = kcalTarget ?: return 0.0
        return (kcal * (pct / 100.0)) / kcalPerGram
    }
    val proteinGrams: Double get() = gramsFor(proteinPct, KCAL_PER_G_PROTEIN)
    val carbsGrams: Double get() = gramsFor(carbsPct, KCAL_PER_G_CARBS)
    val fatGrams: Double get() = gramsFor(fatPct, KCAL_PER_G_FAT)
    val fiberGrams: Double get() = gramsFor(fiberPct, KCAL_PER_G_FIBER)
}

/**
 * Paged create/edit flow for one goal, three pages (dates+calories,
 * macro ratio, meal split) with dot-indicator navigation (see
 * PageIndicator, design discussion) - sits alongside the three
 * existing single-purpose Settings screens, which keep working
 * unchanged for quick tweaks to the active goal without going through
 * this multi-page flow at all. goalId null means creating a new goal;
 * a real id means editing an existing one (fetched fresh on init, not
 * assumed to be the active goal - this flow can open any goal from the
 * Goal List, past or upcoming, not just the current one).
 */
class GoalEditFlowViewModel(private val goalId: Int?) : ViewModel() {

    private val _uiState = MutableStateFlow(GoalEditFlowUiState())
    val uiState: StateFlow<GoalEditFlowUiState> = _uiState

    init {
        if (goalId != null) loadExistingGoal(goalId)
    }

    private fun loadExistingGoal(id: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, loadError = null)
            try {
                val goal = ApiClient.service.getGoal(id)
                val kcal = goal.kcalTarget.toDoubleOrNull() ?: 0.0
                fun pctOf(grams: String, kcalPerGram: Double): Int {
                    val g = grams.toDoubleOrNull() ?: return 0
                    if (kcal <= 0) return 0
                    return ((g * kcalPerGram / kcal) * 100).toInt()
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    startDate = goal.startDate,
                    isOngoing = goal.endDate == null,
                    endDate = goal.endDate ?: "",
                    // Forced to a whole number, not just trailing-zero
                    // trimmed - kcal should never legitimately be
                    // fractional (matches the backend's own ceil_int
                    // convention everywhere else in this app), unlike
                    // the percentages below.
                    kcalTargetInput = kcal.toInt().toString(),
                    proteinPct = pctOf(goal.proteinGTarget, KCAL_PER_G_PROTEIN),
                    carbsPct = pctOf(goal.carbsGTarget, KCAL_PER_G_CARBS),
                    fatPct = pctOf(goal.fatGTarget, KCAL_PER_G_FAT),
                    fiberPct = pctOf(goal.fiberGTarget, KCAL_PER_G_FIBER),
                    // Trailing zeros trimmed only ("30.00" -> "30"),
                    // not force-rounded to a whole number - a genuine
                    // 33.33/33.33/33.34 three-way split is legitimate
                    // and shouldn't become impossible to represent.
                    breakfastPctInput = roundedPctString(goal.mealSplits.firstOrNull { it.mealType == "breakfast" }?.pctOfKcal, "30"),
                    lunchPctInput = roundedPctString(goal.mealSplits.firstOrNull { it.mealType == "lunch" }?.pctOfKcal, "30"),
                    dinnerPctInput = roundedPctString(goal.mealSplits.firstOrNull { it.mealType == "dinner" }?.pctOfKcal, "30"),
                    snackPctInput = roundedPctString(goal.mealSplits.firstOrNull { it.mealType == "snack" }?.pctOfKcal, "10")
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, loadError = e.message ?: "Couldn't load this goal")
            }
        }
    }

    fun goToPage(page: Int) {
        _uiState.value = _uiState.value.copy(page = page.coerceIn(0, 2))
    }

    fun updateStartDate(value: String) { _uiState.value = _uiState.value.copy(startDate = value) }
    fun updateIsOngoing(value: Boolean) { _uiState.value = _uiState.value.copy(isOngoing = value) }
    fun updateEndDate(value: String) { _uiState.value = _uiState.value.copy(endDate = value) }
    fun updateKcalTargetInput(value: String) { _uiState.value = _uiState.value.copy(kcalTargetInput = value) }

    fun adjustProteinPct(delta: Int) { _uiState.value = _uiState.value.copy(proteinPct = (_uiState.value.proteinPct + delta).coerceIn(0, 100)) }
    fun adjustCarbsPct(delta: Int) { _uiState.value = _uiState.value.copy(carbsPct = (_uiState.value.carbsPct + delta).coerceIn(0, 100)) }
    fun adjustFatPct(delta: Int) { _uiState.value = _uiState.value.copy(fatPct = (_uiState.value.fatPct + delta).coerceIn(0, 100)) }
    fun adjustFiberPct(delta: Int) { _uiState.value = _uiState.value.copy(fiberPct = (_uiState.value.fiberPct + delta).coerceIn(0, 100)) }

    fun updateBreakfastPctInput(value: String) { _uiState.value = _uiState.value.copy(breakfastPctInput = value) }
    fun updateLunchPctInput(value: String) { _uiState.value = _uiState.value.copy(lunchPctInput = value) }
    fun updateDinnerPctInput(value: String) { _uiState.value = _uiState.value.copy(dinnerPctInput = value) }
    fun updateSnackPctInput(value: String) { _uiState.value = _uiState.value.copy(snackPctInput = value) }

    fun save(onDone: () -> Unit) {
        val state = _uiState.value
        val kcalTarget = state.kcalTarget
        if (!state.ratioValid || !state.mealSplitValid || kcalTarget == null) return

        val mealSplitRequests = listOf(
            MealGoalSplitRequest("breakfast", state.breakfastPctInput.toDoubleOrNull() ?: 0.0),
            MealGoalSplitRequest("lunch", state.lunchPctInput.toDoubleOrNull() ?: 0.0),
            MealGoalSplitRequest("dinner", state.dinnerPctInput.toDoubleOrNull() ?: 0.0),
            MealGoalSplitRequest("snack", state.snackPctInput.toDoubleOrNull() ?: 0.0)
        )

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, saveError = null)
            try {
                val resolvedGoalId = if (goalId != null) {
                    ApiClient.service.updateGoal(
                        goalId,
                        GoalUpdateRequest(
                            startDate = state.startDate,
                            endDate = if (state.isOngoing) null else state.endDate,
                            kcalTarget = kcalTarget,
                            proteinGTarget = state.proteinGrams,
                            carbsGTarget = state.carbsGrams,
                            fatGTarget = state.fatGrams,
                            fiberGTarget = state.fiberGrams
                        )
                    )
                    goalId
                } else {
                    val created = ApiClient.service.createGoal(
                        GoalCreateRequest(
                            startDate = state.startDate,
                            endDate = if (state.isOngoing) null else state.endDate,
                            kcalTarget = kcalTarget,
                            proteinGTarget = state.proteinGrams,
                            carbsGTarget = state.carbsGrams,
                            fatGTarget = state.fatGrams,
                            fiberGTarget = state.fiberGrams
                        )
                    )
                    created.id
                }
                ApiClient.service.updateMealSplits(resolvedGoalId, MealGoalSplitsUpdateRequest(mealSplitRequests))
                _uiState.value = _uiState.value.copy(isSaving = false)
                onDone()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, saveError = e.message ?: "Couldn't save this goal")
            }
        }
    }
}