package com.mealtracker.android.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.mealtracker.android.network.models.GroceryListEntry
import com.mealtracker.android.network.models.GroceryTrip
import com.mealtracker.android.network.models.Item

/**
 * Grocery List screen (see design discussion) - a pool of not-yet-
 * planned entries grouped by store, and trips as their own sections.
 * "Moving" an entry (pool <-> a trip) is a tap-to-open "Move to..."
 * sheet, not drag-and-drop - Compose has nothing as effortless as
 * HTML5's native drag gesture, and forcing a desktop pattern onto a
 * touchscreen is worse UX than the mobile-native alternative here.
 * Placeholder entries (no real item yet, just a name - see design
 * discussion, the hot dog buns example) show a small badge; tapping it
 * opens an inline search to resolve into a real item, right on the
 * same entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroceryListScreen(viewModel: GroceryListViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.openNewTripSheet() }) {
                Icon(Icons.Filled.Add, contentDescription = "New trip")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.loadError != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.loadError!!, color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { viewModel.load() }) { Text("Retry") }
                        }
                    }
                }
                else -> {
                    GroceryListContent(state = state, viewModel = viewModel)
                }
            }
        }
    }

    if (state.movingEntryId != null) {
        MoveEntrySheet(
            entry = state.entries.first { it.id == state.movingEntryId },
            trips = state.trips,
            error = state.moveError,
            onMoveToPool = { viewModel.moveEntryToTrip(state.movingEntryId!!, null) },
            onMoveToTrip = { tripId -> viewModel.moveEntryToTrip(state.movingEntryId!!, tripId) },
            onDismiss = { viewModel.dismissMoveSheet() }
        )
    }

    if (state.showNewTripSheet) {
        NewTripSheet(state = state, viewModel = viewModel)
    }

    if (state.resolvingEntryId != null) {
        ResolvePlaceholderDialog(
            query = state.resolveSearchQuery,
            results = state.resolveSearchResults,
            isSearching = state.isSearchingResolve,
            onQueryChange = { viewModel.updateResolveSearchQuery(it) },
            onPick = { item -> viewModel.resolveEntry(state.resolvingEntryId!!, item.itemId) },
            onDismiss = { viewModel.dismissResolveSearch() }
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
private fun GroceryListContent(state: GroceryListUiState, viewModel: GroceryListViewModel) {
    val poolEntries = state.entries.filter { it.tripId == null }
    // Grouped by store, same bucketing logic as the web app - a
    // store-agnostic item (or a placeholder, which has no stores at
    // all) lands in "Any store".
    val poolByStore = linkedMapOf<String, Pair<String, MutableList<GroceryListEntry>>>()
    for (entry in poolEntries) {
        if (entry.groceryStores.isEmpty()) {
            poolByStore.getOrPut("any") { "Any store" to mutableListOf() }.second.add(entry)
        } else {
            for (store in entry.groceryStores) {
                poolByStore.getOrPut(store.id.toString()) { store.name to mutableListOf() }.second.add(entry)
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
            Text("Not yet planned", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
        }
        if (poolByStore.isEmpty()) {
            item {
                Text(
                    "Nothing here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
        poolByStore.forEach { (_, pair) ->
            val (storeName, storeEntries) = pair
            item {
                Text(
                    storeName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
            }
            items(storeEntries, key = { "pool-${it.id}" }) { entry ->
                GroceryEntryRow(
                    entry = entry,
                    onClick = { viewModel.openMoveSheet(entry.id) },
                    onDelete = { viewModel.deleteEntry(entry.id) },
                    onResolveClick = { viewModel.openResolveSearch(entry.id) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            NewPlaceholderRow(
                input = state.newPlaceholderInput,
                isAdding = state.isAddingPlaceholder,
                onInputChange = { viewModel.updateNewPlaceholderInput(it) },
                onAdd = { viewModel.addPlaceholder() }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text("Trips", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (state.trips.isEmpty()) {
            item {
                Text(
                    "No trips planned yet - tap + to add one",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
        state.trips.sortedBy { it.date }.forEach { trip ->
            val tripEntries = state.entries.filter { it.tripId == trip.id }
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(trip.label ?: trip.date, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${if (trip.label != null) "${trip.date} \u00b7 " else ""}${trip.store?.name ?: "Any store"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { viewModel.deleteTrip(trip.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete trip")
                            }
                        }
                        if (tripEntries.isEmpty()) {
                            Text(
                                "Nothing planned for this trip yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            Column {
                                tripEntries.forEach { entry ->
                                    GroceryEntryRow(
                                        entry = entry,
                                        onClick = { viewModel.openMoveSheet(entry.id) },
                                        onDelete = { viewModel.deleteEntry(entry.id) },
                                        onResolveClick = { viewModel.openResolveSearch(entry.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(72.dp)) } // room for the FAB
    }
}

@Composable
private fun GroceryEntryRow(
    entry: GroceryListEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onResolveClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.itemName, style = MaterialTheme.typography.bodyMedium)
                if (!entry.quantity.isNullOrBlank()) {
                    Text(entry.quantity, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Close, contentDescription = "Remove")
            }
        }
        if (entry.isPlaceholder) {
            Surface(
                onClick = onResolveClick,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    "Still needs info \u00b7 tap to find the real item",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun NewPlaceholderRow(input: String, isAdding: Boolean, onInputChange: (String) -> Unit, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            placeholder = { Text("No product yet? Add a name") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(onClick = onAdd, enabled = !isAdding && input.isNotBlank()) {
            Text(if (isAdding) "Adding..." else "Add")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveEntrySheet(
    entry: GroceryListEntry,
    trips: List<GroceryTrip>,
    error: String?,
    onMoveToPool: () -> Unit,
    onMoveToTrip: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Move \"${entry.itemName}\" to...", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
            }
            Surface(onClick = onMoveToPool, modifier = Modifier.fillMaxWidth()) {
                Text("Not yet planned (pool)", modifier = Modifier.padding(vertical = 12.dp))
            }
            trips.sortedBy { it.date }.forEach { trip ->
                Surface(onClick = { onMoveToTrip(trip.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(trip.label ?: trip.date, modifier = Modifier.padding(vertical = 12.dp))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewTripSheet(state: GroceryListUiState, viewModel: GroceryListViewModel) {
    val sheetState = rememberModalBottomSheetState()
    var storeMenuExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = { viewModel.dismissNewTripSheet() }, sheetState = sheetState) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("New trip", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = state.newTripDate,
                onValueChange = { viewModel.updateNewTripDate(it) },
                label = { Text("Date (YYYY-MM-DD)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.newTripLabel,
                onValueChange = { viewModel.updateNewTripLabel(it) },
                label = { Text("Label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Plain OutlinedTextField + plain DropdownMenu, not
            // ExposedDropdownMenuBox - the latter failed to compile
            // twice in a row on this project's exact Compose version
            // despite two different fix attempts (menuAnchor's
            // signature, then ExposedDropdownMenu's own resolution).
            // DropdownMenu/DropdownMenuItem are much older, simpler,
            // unambiguous top-level composables - a small loss of
            // polish (no built-in "looks like part of the text field"
            // chrome) for something I can be far more confident
            // actually builds.
            // A plain clickable Box styled to look like a text field,
            // not an actual OutlinedTextField - the latter has its own
            // internal pointer-input handling (for text selection,
            // cursor placement, focus) that consumes taps before they
            // ever reach an outer Modifier.clickable, even with
            // readOnly = true (that only blocks text EDITING, not the
            // field's own gesture handling) - this was exactly why
            // tapping it did nothing (see design discussion / bug
            // report). Same reasoning as replacing
            // ExposedDropdownMenuBox earlier: a simpler component with
            // no competing internal gesture handling, rather than
            // fighting the fancier one's.
            val selectedStoreName = state.stores.firstOrNull { it.id == state.newTripStoreId }?.name ?: "Any store"
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                        .clickable { storeMenuExpanded = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text("Store", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(selectedStoreName, style = MaterialTheme.typography.bodyLarge)
                }
                androidx.compose.material3.DropdownMenu(expanded = storeMenuExpanded, onDismissRequest = { storeMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Any store") },
                        onClick = { viewModel.updateNewTripStore(null); storeMenuExpanded = false }
                    )
                    state.stores.forEach { store ->
                        DropdownMenuItem(
                            text = { Text(store.name) },
                            onClick = { viewModel.updateNewTripStore(store.id); storeMenuExpanded = false }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.newStoreNameInput,
                    onValueChange = { viewModel.updateNewStoreName(it) },
                    label = { Text("New store name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = { viewModel.createStoreForNewTrip() },
                    enabled = !state.isCreatingStore && state.newStoreNameInput.isNotBlank()
                ) {
                    Text(if (state.isCreatingStore) "..." else "Add")
                }
            }

            if (state.newTripError != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(state.newTripError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { viewModel.createTrip() },
                enabled = !state.isCreatingTrip && state.newTripDate.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isCreatingTrip) "Creating..." else "Create trip")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ResolvePlaceholderDialog(
    query: String,
    results: List<Item>,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onPick: (Item) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Find the real item") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Search your items...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (isSearching) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                    }
                } else {
                    Column {
                        results.take(8).forEach { item ->
                            Surface(onClick = { onPick(item) }, modifier = Modifier.fillMaxWidth()) {
                                Text(item.name, modifier = Modifier.padding(vertical = 10.dp))
                            }
                        }
                    }
                }
            }
        }
    )
}