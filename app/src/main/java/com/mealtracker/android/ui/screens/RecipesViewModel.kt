package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.Recipe
import com.mealtracker.android.network.models.RecipeDetail
import com.mealtracker.android.network.models.RecipeStepCreateRequest
import com.mealtracker.android.network.models.RecipeStepUpdateRequest
import com.mealtracker.android.network.models.RecipeUpdateRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val SEARCH_DEBOUNCE_MS = 350L

/**
 * Backs the new Recipes tab (see design discussion: replacing Meal Plan
 * -- pre-logging real meals directly into the Journal already served
 * that planning need, so the separate draft-then-commit staging area
 * wasn't earning its keep). Browse-only for ingredients (add/remove
 * ingredients still happens through the meal-logging-anchored
 * RecipeInfoScreen in MealDetailScreen.kt, reached by actually logging
 * a recipe) -- this screen's own editing is limited to metadata: name,
 * instructions, source URL, servings, and the hero image. Both
 * recipe_type values are listed together and both get the full
 * instructions/source-URL treatment, since they share the same
 * underlying table/column and a "meal" can be just as involved as a
 * recipe (see design discussion: pancakes saved as a meal still
 * benefit from having steps/a source).
 */
data class RecipesUiState(
    val isLoadingList: Boolean = true,
    val recipes: List<Recipe> = emptyList(),
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val listError: String? = null,

    val selectedRecipeId: Int? = null,
    val recipeDetail: RecipeDetail? = null,
    val isLoadingDetail: Boolean = false,
    val detailError: String? = null,

    val isEditing: Boolean = false,
    val editName: String = "",
    val editSourceUrl: String = "",
    val editServings: String = "1",
    val isSaving: Boolean = false,
    val saveError: String? = null,

    // Steps (see design discussion) - own add/edit/delete flow, not
    // bundled into the metadata save above. newStepText/newStepTimer
    // back the "+" pane; the rest are per-step-id maps so several rows
    // can hold independent unsaved edits at once without stepping on
    // each other.
    val newStepText: String = "",
    val newStepTimerInput: String = "",
    val isAddingStep: Boolean = false,
    val stepsError: String? = null,
    val editingStepId: Int? = null,
    val editStepText: String = "",
    val editStepTimerInput: String = "",
    val isSavingStep: Boolean = false,

    val isUploadingImage: Boolean = false,
    val imageError: String? = null
)

class RecipesViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(RecipesUiState())
    val uiState: StateFlow<RecipesUiState> = _uiState

    init {
        loadList()
    }

    private var searchJob: Job? = null

    fun loadList() {
        _uiState.value = _uiState.value.copy(isLoadingList = true, listError = null)
        viewModelScope.launch {
            try {
                val recipes = ApiClient.service.searchRecipes(query = null, recipeType = null, limit = 200)
                _uiState.value = _uiState.value.copy(isLoadingList = false, recipes = recipes)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingList = false,
                    listError = e.message ?: "Couldn't load recipes"
                )
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            loadList()
            return
        }
        _uiState.value = _uiState.value.copy(isSearching = true)
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            try {
                val recipes = ApiClient.service.searchRecipes(query = query, recipeType = null, limit = 200)
                if (_uiState.value.searchQuery == query) {
                    _uiState.value = _uiState.value.copy(isSearching = false, recipes = recipes)
                }
            } catch (e: Exception) {
                if (_uiState.value.searchQuery == query) {
                    _uiState.value = _uiState.value.copy(
                        isSearching = false,
                        listError = e.message ?: "Couldn't search recipes"
                    )
                }
            }
        }
    }

    fun openRecipe(recipeId: Int) {
        _uiState.value = _uiState.value.copy(
            selectedRecipeId = recipeId,
            isLoadingDetail = true,
            detailError = null,
            isEditing = false
        )
        viewModelScope.launch {
            try {
                val detail = ApiClient.service.getRecipe(recipeId)
                _uiState.value = _uiState.value.copy(isLoadingDetail = false, recipeDetail = detail)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingDetail = false,
                    detailError = e.message ?: "Couldn't load that recipe"
                )
            }
        }
    }

    fun dismissDetail() {
        _uiState.value = _uiState.value.copy(selectedRecipeId = null, recipeDetail = null, detailError = null)
    }

    fun startEditing() {
        val detail = _uiState.value.recipeDetail ?: return
        _uiState.value = _uiState.value.copy(
            isEditing = true,
            editName = detail.name,
            editSourceUrl = detail.sourceUrl ?: "",
            editServings = detail.servings,
            saveError = null
        )
    }

    fun cancelEditing() {
        _uiState.value = _uiState.value.copy(isEditing = false, saveError = null)
    }

    fun updateEditName(value: String) { _uiState.value = _uiState.value.copy(editName = value) }
    fun updateEditSourceUrl(value: String) { _uiState.value = _uiState.value.copy(editSourceUrl = value) }
    // Digits only -- same reasoning as CreateRecipeViewModel.updateServings:
    // a recipe's own yield count is a whole number, unlike a logged
    // quantity of servings of it (which can be fractional).
    fun updateEditServings(value: String) { _uiState.value = _uiState.value.copy(editServings = value.filter { it.isDigit() }) }

    fun saveEdits() {
        val state = _uiState.value
        val recipeId = state.selectedRecipeId ?: return
        val detail = state.recipeDetail ?: return
        val name = state.editName.trim()
        if (name.isEmpty()) {
            _uiState.value = state.copy(saveError = "Name can't be empty")
            return
        }
        // Meals are always exactly 1 serving (not user-editable, same
        // convention as the meal-logging-anchored RecipeInfoScreen) --
        // only recipes get an editable servings count.
        val servings = if (detail.recipeType == "meal") null else state.editServings.toDoubleOrNull()

        _uiState.value = state.copy(isSaving = true, saveError = null)
        viewModelScope.launch {
            try {
                val updated = ApiClient.service.updateRecipe(
                    recipeId,
                    RecipeUpdateRequest(
                        name = name,
                        sourceUrl = state.editSourceUrl.trim().ifEmpty { null },
                        servings = servings
                    )
                )
                _uiState.value = _uiState.value.copy(isSaving = false, isEditing = false, recipeDetail = updated)
                loadList()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, saveError = e.message ?: "Couldn't save")
            }
        }
    }

    fun deleteRecipe() {
        val recipeId = _uiState.value.selectedRecipeId ?: return
        viewModelScope.launch {
            try {
                ApiClient.service.deleteRecipe(recipeId)
                dismissDetail()
                loadList()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(detailError = e.message ?: "Couldn't delete")
            }
        }
    }

    fun updateRecipeImage(imagePath: String) {
        val recipeId = _uiState.value.selectedRecipeId ?: return
        _uiState.value = _uiState.value.copy(isUploadingImage = true, imageError = null)
        viewModelScope.launch {
            try {
                val updated = ApiClient.service.updateRecipe(recipeId, RecipeUpdateRequest(imagePath = imagePath))
                _uiState.value = _uiState.value.copy(isUploadingImage = false, recipeDetail = updated)
                loadList()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isUploadingImage = false,
                    imageError = e.message ?: "Couldn't update photo"
                )
            }
        }
    }

    // --- Steps (see design discussion) ---

    fun updateNewStepText(value: String) { _uiState.value = _uiState.value.copy(newStepText = value) }
    fun updateNewStepTimerInput(value: String) { _uiState.value = _uiState.value.copy(newStepTimerInput = value.filter { it.isDigit() }) }

    fun addStep() {
        val recipeId = _uiState.value.selectedRecipeId ?: return
        val text = _uiState.value.newStepText.trim()
        if (text.isEmpty()) return
        val timerSeconds = _uiState.value.newStepTimerInput.toIntOrNull()

        _uiState.value = _uiState.value.copy(isAddingStep = true, stepsError = null)
        viewModelScope.launch {
            try {
                val updated = ApiClient.service.addRecipeStep(recipeId, RecipeStepCreateRequest(text = text, timerSeconds = timerSeconds))
                _uiState.value = _uiState.value.copy(
                    recipeDetail = updated,
                    newStepText = "",
                    newStepTimerInput = "",
                    isAddingStep = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isAddingStep = false, stepsError = e.message ?: "Couldn't add that step")
            }
        }
    }

    /** Tap-to-edit, one step at a time - not every row holding its own
     * always-editable local state - simpler on a touchscreen, and
     * "add one step at a time" was the actual ask, not simultaneous
     * multi-row editing. */
    fun startEditingStep(stepId: Int) {
        val step = _uiState.value.recipeDetail?.steps?.firstOrNull { it.id == stepId } ?: return
        _uiState.value = _uiState.value.copy(
            editingStepId = stepId,
            editStepText = step.text,
            editStepTimerInput = step.timerSeconds?.toString() ?: "",
            stepsError = null
        )
    }

    fun cancelEditingStep() {
        _uiState.value = _uiState.value.copy(editingStepId = null, stepsError = null)
    }

    fun updateEditStepText(value: String) { _uiState.value = _uiState.value.copy(editStepText = value) }
    fun updateEditStepTimerInput(value: String) { _uiState.value = _uiState.value.copy(editStepTimerInput = value.filter { it.isDigit() }) }

    fun saveEditingStep() {
        val recipeId = _uiState.value.selectedRecipeId ?: return
        val stepId = _uiState.value.editingStepId ?: return
        val text = _uiState.value.editStepText.trim()
        if (text.isEmpty()) return
        val timerSeconds = _uiState.value.editStepTimerInput.toIntOrNull()

        _uiState.value = _uiState.value.copy(isSavingStep = true, stepsError = null)
        viewModelScope.launch {
            try {
                val updated = ApiClient.service.updateRecipeStep(recipeId, stepId, RecipeStepUpdateRequest(text = text, timerSeconds = timerSeconds))
                _uiState.value = _uiState.value.copy(recipeDetail = updated, editingStepId = null, isSavingStep = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSavingStep = false, stepsError = e.message ?: "Couldn't save that step")
            }
        }
    }

    fun deleteStep(stepId: Int) {
        val recipeId = _uiState.value.selectedRecipeId ?: return
        viewModelScope.launch {
            try {
                val updated = ApiClient.service.deleteRecipeStep(recipeId, stepId)
                _uiState.value = _uiState.value.copy(recipeDetail = updated, editingStepId = null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(stepsError = e.message ?: "Couldn't remove that step")
            }
        }
    }
}