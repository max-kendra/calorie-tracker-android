package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.Goal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class GoalListUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val goals: List<Goal> = emptyList(),
    val deletingGoalId: Int? = null,
    val actionError: String? = null
)

/**
 * Goal List screen (see design discussion) - the entry point into
 * managing goal history/scheduling, sitting alongside (not replacing)
 * the three existing quick-edit Settings screens, which keep working
 * exactly as before for tweaking the current goal's individual
 * sections without going through this at all.
 */
class GoalListViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(GoalListUiState())
    val uiState: StateFlow<GoalListUiState> = _uiState

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, loadError = null)
            try {
                val goals = ApiClient.service.getGoalsList()
                _uiState.value = _uiState.value.copy(isLoading = false, goals = goals)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, loadError = e.message ?: "Couldn't load goals")
            }
        }
    }

    fun deleteGoal(goalId: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(deletingGoalId = goalId, actionError = null)
            try {
                ApiClient.service.deleteGoal(goalId)
                _uiState.value = _uiState.value.copy(
                    goals = _uiState.value.goals.filter { it.id != goalId },
                    deletingGoalId = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(deletingGoalId = null, actionError = e.message ?: "Couldn't delete this goal")
            }
        }
    }

    fun dismissActionError() {
        _uiState.value = _uiState.value.copy(actionError = null)
    }
}

enum class GoalStatus { CURRENT, UPCOMING, PAST }

/** Same grouping logic as the web app's own goal list. */
fun goalStatus(goal: Goal, today: LocalDate = LocalDate.now()): GoalStatus {
    val todayIso = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
    if (goal.endDate != null && goal.endDate < todayIso) return GoalStatus.PAST
    if (goal.startDate > todayIso) return GoalStatus.UPCOMING
    return GoalStatus.CURRENT
}