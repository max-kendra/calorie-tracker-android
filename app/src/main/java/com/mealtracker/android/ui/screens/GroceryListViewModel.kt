package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.GroceryListEntry
import com.mealtracker.android.network.models.GroceryListEntryCreateRequest
import com.mealtracker.android.network.models.GroceryStore
import com.mealtracker.android.network.models.GroceryStoreCreateRequest
import com.mealtracker.android.network.models.GroceryTrip
import com.mealtracker.android.network.models.GroceryTripCreateRequest
import com.mealtracker.android.network.models.Item
import com.mealtracker.android.network.models.MoveEntryToTripRequest
import com.mealtracker.android.network.models.ResolveEntryRequest
import com.mealtracker.android.network.models.UpdateEntryQuantityRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val RESOLVE_SEARCH_DEBOUNCE_MS = 350L

data class GroceryListUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val entries: List<GroceryListEntry> = emptyList(),
    val trips: List<GroceryTrip> = emptyList(),
    val stores: List<GroceryStore> = emptyList(),

    // "Move to..." sheet - tap-to-move instead of drag-and-drop (see
    // design discussion: Compose has nothing as effortless as HTML5's
    // native drag gesture, and forcing a desktop pattern onto a
    // touchscreen is worse UX than the mobile-native alternative).
    val movingEntryId: Int? = null,
    val moveError: String? = null,

    // New trip sheet
    val showNewTripSheet: Boolean = false,
    val newTripDate: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
    val newTripLabel: String = "",
    val newTripStoreId: Int? = null,
    val isCreatingTrip: Boolean = false,
    val newTripError: String? = null,

    // Plain-text placeholder entry (see design discussion: the hot dog
    // buns example - knowing you need something before you've
    // found/scanned the actual product).
    val newPlaceholderInput: String = "",
    val isAddingPlaceholder: Boolean = false,

    // Inline resolve-placeholder search, opened per-entry.
    val resolvingEntryId: Int? = null,
    val resolveSearchQuery: String = "",
    val resolveSearchResults: List<Item> = emptyList(),
    val isSearchingResolve: Boolean = false,

    // New-store inline create, reused from the same pattern as the
    // item edit dialog's checklist.
    val newStoreNameInput: String = "",
    val isCreatingStore: Boolean = false,

    val actionError: String? = null
)

class GroceryListViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(GroceryListUiState())
    val uiState: StateFlow<GroceryListUiState> = _uiState

    private var resolveSearchJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, loadError = null)
            try {
                val entries = ApiClient.service.getGroceryEntries()
                val trips = ApiClient.service.getGroceryTrips()
                val stores = ApiClient.service.getGroceryStores()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    entries = entries,
                    trips = trips,
                    stores = stores
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, loadError = e.message ?: "Couldn't load the grocery list")
            }
        }
    }

    // --- Move entry between pool/trips ---

    fun openMoveSheet(entryId: Int) {
        _uiState.value = _uiState.value.copy(movingEntryId = entryId, moveError = null)
    }

    fun dismissMoveSheet() {
        _uiState.value = _uiState.value.copy(movingEntryId = null, moveError = null)
    }

    /** tripId null moves the entry back to the unassigned pool. A 409
     * means the trip has a store this item isn't compatible with (see
     * backend's _check_trip_compatibility) - shown as a plain message,
     * not a crash, and the sheet stays open so another trip can be
     * picked instead. */
    fun moveEntryToTrip(entryId: Int, tripId: Int?) {
        viewModelScope.launch {
            try {
                val updated = ApiClient.service.moveGroceryEntryToTrip(entryId, MoveEntryToTripRequest(tripId = tripId))
                _uiState.value = _uiState.value.copy(
                    entries = _uiState.value.entries.map { if (it.id == entryId) updated else it },
                    movingEntryId = null,
                    moveError = null
                )
            } catch (e: HttpException) {
                val message = if (e.code() == 409) {
                    "This item isn't carried by that trip's store"
                } else {
                    e.message() ?: "Couldn't move this item"
                }
                _uiState.value = _uiState.value.copy(moveError = message)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(moveError = e.message ?: "Couldn't move this item")
            }
        }
    }

    fun deleteEntry(entryId: Int) {
        viewModelScope.launch {
            try {
                ApiClient.service.deleteGroceryEntry(entryId)
                _uiState.value = _uiState.value.copy(entries = _uiState.value.entries.filter { it.id != entryId })
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(actionError = e.message ?: "Couldn't remove this item")
            }
        }
    }

    fun updateEntryQuantity(entryId: Int, quantity: String?) {
        viewModelScope.launch {
            try {
                val updated = ApiClient.service.updateGroceryEntryQuantity(entryId, UpdateEntryQuantityRequest(quantity = quantity))
                _uiState.value = _uiState.value.copy(entries = _uiState.value.entries.map { if (it.id == entryId) updated else it })
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(actionError = e.message ?: "Couldn't update the quantity")
            }
        }
    }

    // --- Placeholder entries (see design discussion) ---

    fun updateNewPlaceholderInput(value: String) {
        _uiState.value = _uiState.value.copy(newPlaceholderInput = value)
    }

    fun addPlaceholder() {
        val name = _uiState.value.newPlaceholderInput.trim()
        if (name.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAddingPlaceholder = true, actionError = null)
            try {
                val entry = ApiClient.service.createGroceryEntry(GroceryListEntryCreateRequest(placeholderName = name))
                _uiState.value = _uiState.value.copy(
                    entries = _uiState.value.entries + entry,
                    newPlaceholderInput = "",
                    isAddingPlaceholder = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isAddingPlaceholder = false, actionError = e.message ?: "Couldn't add that")
            }
        }
    }

    fun openResolveSearch(entryId: Int) {
        _uiState.value = _uiState.value.copy(resolvingEntryId = entryId, resolveSearchQuery = "", resolveSearchResults = emptyList())
    }

    fun dismissResolveSearch() {
        resolveSearchJob?.cancel()
        _uiState.value = _uiState.value.copy(resolvingEntryId = null, resolveSearchQuery = "", resolveSearchResults = emptyList())
    }

    fun updateResolveSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(resolveSearchQuery = query)
        resolveSearchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(resolveSearchResults = emptyList(), isSearchingResolve = false)
            return
        }
        resolveSearchJob = viewModelScope.launch {
            delay(RESOLVE_SEARCH_DEBOUNCE_MS)
            _uiState.value = _uiState.value.copy(isSearchingResolve = true)
            try {
                val results = ApiClient.service.searchItems(query = query)
                _uiState.value = _uiState.value.copy(resolveSearchResults = results, isSearchingResolve = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSearchingResolve = false)
            }
        }
    }

    /** Converts a placeholder into a real item on the SAME entry (see
     * design discussion) - not a separate create+delete. Can 409 the
     * same way a move can, if the entry is already sitting in a
     * store-specific trip this item isn't compatible with. */
    fun resolveEntry(entryId: Int, itemId: Int) {
        viewModelScope.launch {
            try {
                val updated = ApiClient.service.resolveGroceryEntry(entryId, ResolveEntryRequest(itemId = itemId))
                _uiState.value = _uiState.value.copy(
                    entries = _uiState.value.entries.map { if (it.id == entryId) updated else it },
                    resolvingEntryId = null,
                    resolveSearchQuery = "",
                    resolveSearchResults = emptyList()
                )
            } catch (e: HttpException) {
                val message = if (e.code() == 409) {
                    "This item isn't carried by the store this trip is assigned to"
                } else {
                    e.message() ?: "Couldn't resolve this item"
                }
                _uiState.value = _uiState.value.copy(actionError = message)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(actionError = e.message ?: "Couldn't resolve this item")
            }
        }
    }

    // --- Trips ---

    fun openNewTripSheet() {
        _uiState.value = _uiState.value.copy(
            showNewTripSheet = true,
            newTripDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
            newTripLabel = "",
            newTripStoreId = null,
            newTripError = null
        )
    }

    fun dismissNewTripSheet() {
        _uiState.value = _uiState.value.copy(showNewTripSheet = false, newTripError = null)
    }

    fun updateNewTripDate(value: String) {
        _uiState.value = _uiState.value.copy(newTripDate = value)
    }

    fun updateNewTripLabel(value: String) {
        _uiState.value = _uiState.value.copy(newTripLabel = value)
    }

    fun updateNewTripStore(storeId: Int?) {
        _uiState.value = _uiState.value.copy(newTripStoreId = storeId)
    }

    fun createTrip() {
        val state = _uiState.value
        if (state.newTripDate.isBlank()) return
        viewModelScope.launch {
            _uiState.value = state.copy(isCreatingTrip = true, newTripError = null)
            try {
                val trip = ApiClient.service.createGroceryTrip(
                    GroceryTripCreateRequest(
                        date = state.newTripDate,
                        label = state.newTripLabel.trim().ifBlank { null },
                        storeId = state.newTripStoreId
                    )
                )
                _uiState.value = _uiState.value.copy(
                    trips = _uiState.value.trips + trip,
                    showNewTripSheet = false,
                    isCreatingTrip = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isCreatingTrip = false, newTripError = e.message ?: "Couldn't create the trip")
            }
        }
    }

    /** Deleting a trip drops its entries back into the unassigned pool
     * (trip_id -> NULL server-side, see the backend's own delete_trip
     * docstring) rather than deleting them - reload rather than patch
     * local state, since every affected entry's tripId changes at
     * once. */
    fun deleteTrip(tripId: Int) {
        viewModelScope.launch {
            try {
                ApiClient.service.deleteGroceryTrip(tripId)
                _uiState.value = _uiState.value.copy(trips = _uiState.value.trips.filter { it.id != tripId })
                load()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(actionError = e.message ?: "Couldn't delete this trip")
            }
        }
    }

    // --- Inline "+ new store" (same pattern as the item edit dialog) ---

    fun updateNewStoreName(value: String) {
        _uiState.value = _uiState.value.copy(newStoreNameInput = value)
    }

    fun createStoreForNewTrip() {
        val name = _uiState.value.newStoreNameInput.trim()
        if (name.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCreatingStore = true)
            try {
                val store = ApiClient.service.createGroceryStore(GroceryStoreCreateRequest(name = name))
                _uiState.value = _uiState.value.copy(
                    stores = _uiState.value.stores + store,
                    newTripStoreId = store.id,
                    newStoreNameInput = "",
                    isCreatingStore = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isCreatingStore = false, newTripError = e.message ?: "Couldn't create store")
            }
        }
    }

    fun dismissActionError() {
        _uiState.value = _uiState.value.copy(actionError = null)
    }
}