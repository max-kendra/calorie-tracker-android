package com.mealtracker.android.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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

    Box(modifier = Modifier.fillMaxSize()) {
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
        val addItemFlow = state.addItemFlow
        if (addItemFlow != null) {
            AddItemFlowOverlay(
                request = addItemFlow,
                onUseItem = { item -> viewModel.addExistingItem(item) },
                onClose = { viewModel.closeAddItemFlow() }
            )
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

private val TRIP_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM")

private fun tripDateLabel(date: String): String =
    try {
        LocalDate.parse(date).format(TRIP_DATE_FORMAT)
    } catch (e: Exception) {
        date
    }

private fun tripTitle(trip: GroceryTrip): String = trip.label ?: tripDateLabel(trip.date)

/** One list per pager page (page 0 = unplanned, then one page per trip,
 * soonest first), with chips on top as both tabs and page indicator.
 * The draggable card at the bottom adds to whichever list is showing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroceryListContent(state: GroceryListUiState, viewModel: GroceryListViewModel) {
    var quantityEntry by remember { mutableStateOf<GroceryListEntry?>(null) }
    var completingTrip by remember { mutableStateOf<GroceryTrip?>(null) }

    quantityEntry?.let { entry ->
        QuantityDialog(
            itemName = entry.itemName,
            initial = entry.quantity.orEmpty(),
            onSave = { value ->
                viewModel.updateEntryQuantity(entry.id, value.trim().ifBlank { null })
                quantityEntry = null
            },
            onDismiss = { quantityEntry = null }
        )
    }

    completingTrip?.let { trip ->
        val count = state.entries.count { it.tripId == trip.id }
        AlertDialog(
            onDismissRequest = { completingTrip = null },
            title = { Text("Complete this trip?") },
            text = { Text("Everything planned for it ($count items) is treated as bought and removed from the list.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.completeTrip(trip.id)
                    completingTrip = null
                }) { Text("Complete") }
            },
            dismissButton = { TextButton(onClick = { completingTrip = null }) { Text("Cancel") } }
        )
    }

    val sortedTrips = remember(state.trips) { state.trips.sortedBy { it.date } }
    val pagerState = rememberPagerState(pageCount = { 1 + sortedTrips.size })
    val scaffoldState = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val expandedSheetMinHeight = LocalConfiguration.current.screenHeightDp.dp * 0.82f

    val currentTrips by rememberUpdatedState(sortedTrips)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage to currentTrips }.collect { (page, trips) ->
            viewModel.selectList(trips.getOrNull(page - 1)?.id)
        }
    }

    LaunchedEffect(state.newlyCreatedTripId) {
        val id = state.newlyCreatedTripId ?: return@LaunchedEffect
        val index = sortedTrips.indexOfFirst { it.id == id }
        if (index >= 0) {
            pagerState.animateScrollToPage(index + 1)
            viewModel.consumeNewlyCreatedTrip()
        }
    }

    // Collapse the add card once something lands, so the list is visible.
    var knownEntryCount by remember { mutableStateOf(state.entries.size) }
    LaunchedEffect(state.entries.size) {
        if (state.entries.size > knownEntryCount) {
            focusManager.clearFocus()
            scaffoldState.bottomSheetState.partialExpand()
        }
        knownEntryCount = state.entries.size
    }

    val activeTrip = sortedTrips.getOrNull(pagerState.currentPage - 1)

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetPeekHeight = 140.dp,
        sheetContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = expandedSheetMinHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            ) {
                Text(
                    "Add to ${activeTrip?.let { tripTitle(it) } ?: "Unplanned"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                AddToListSection(
                    state = state,
                    viewModel = viewModel,
                    onFieldFocused = { scope.launch { scaffoldState.bottomSheetState.expand() } }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = pagerState.currentPage == 0,
                            onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                            label = { Text("Unplanned (${state.entries.count { it.tripId == null }})") }
                        )
                    }
                    itemsIndexed(sortedTrips, key = { _, trip -> trip.id }) { index, trip ->
                        FilterChip(
                            selected = pagerState.currentPage == index + 1,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index + 1) } },
                            label = { Text("${tripTitle(trip)} (${state.entries.count { it.tripId == trip.id }})") }
                        )
                    }
                }

                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                    val trip = sortedTrips.getOrNull(page - 1)
                    when {
                        page == 0 -> UnplannedPage(
                            entries = state.entries.filter { it.tripId == null },
                            viewModel = viewModel,
                            onQuantityClick = { quantityEntry = it }
                        )
                        trip != null -> TripPage(
                            trip = trip,
                            entries = state.entries.filter { it.tripId == trip.id },
                            viewModel = viewModel,
                            onComplete = { completingTrip = trip },
                            onQuantityClick = { quantityEntry = it }
                        )
                        else -> Box(modifier = Modifier.fillMaxSize())
                    }
                }
            }

            FloatingActionButton(
                onClick = { viewModel.openNewTripSheet() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New trip")
            }
        }
    }
}

@Composable
private fun UnplannedPage(
    entries: List<GroceryListEntry>,
    viewModel: GroceryListViewModel,
    onQuantityClick: (GroceryListEntry) -> Unit
) {
    // Grouped by store, same bucketing logic as the web app - a
    // store-agnostic item (or a placeholder, which has no stores at
    // all) lands in "Any store".
    val byStore = linkedMapOf<String, Pair<String, MutableList<GroceryListEntry>>>()
    for (entry in entries) {
        if (entry.groceryStores.isEmpty()) {
            byStore.getOrPut("any") { "Any store" to mutableListOf() }.second.add(entry)
        } else {
            for (store in entry.groceryStores) {
                byStore.getOrPut(store.id.toString()) { store.name to mutableListOf() }.second.add(entry)
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (byStore.isEmpty()) {
            item {
                Text(
                    "Nothing unplanned. Use the card below to add items.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
        byStore.forEach { (key, pair) ->
            val (storeName, storeEntries) = pair
            item(key = "store-$key") {
                Text(
                    storeName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
            }
            items(storeEntries, key = { "pool-$key-${it.id}" }) { entry ->
                GroceryEntryRow(
                    entry = entry,
                    onClick = { viewModel.openMoveSheet(entry.id) },
                    onDelete = { viewModel.deleteEntry(entry.id) },
                    onResolveClick = { viewModel.openResolveSearch(entry.id) },
                    onQuantityClick = { onQuantityClick(entry) }
                )
            }
        }
        item { Spacer(modifier = Modifier.height(88.dp)) } // room for the FAB
    }
}

@Composable
private fun TripPage(
    trip: GroceryTrip,
    entries: List<GroceryListEntry>,
    viewModel: GroceryListViewModel,
    onComplete: () -> Unit,
    onQuantityClick: (GroceryListEntry) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tripTitle(trip), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${if (trip.label != null) "${tripDateLabel(trip.date)} \u00b7 " else ""}" +
                            "${trip.store?.name ?: "Any store"} \u00b7 ${entries.size} ${if (entries.size == 1) "item" else "items"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(onClick = onComplete) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Complete")
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete trip (items return to Unplanned)") },
                            onClick = {
                                menuOpen = false
                                viewModel.deleteTrip(trip.id)
                            }
                        )
                    }
                }
            }
            HorizontalDivider()
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    "Nothing planned for this trip yet. Add items with the card below, or move them here from Unplanned.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
        items(entries, key = { "trip-${trip.id}-${it.id}" }) { entry ->
            GroceryEntryRow(
                entry = entry,
                onClick = { viewModel.openMoveSheet(entry.id) },
                onDelete = { viewModel.deleteEntry(entry.id) },
                onResolveClick = { viewModel.openResolveSearch(entry.id) },
                onQuantityClick = { onQuantityClick(entry) }
            )
        }
        item { Spacer(modifier = Modifier.height(88.dp)) } // room for the FAB
    }
}

@Composable
private fun GroceryEntryRow(
    entry: GroceryListEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onResolveClick: () -> Unit,
    onQuantityClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.itemName, style = MaterialTheme.typography.bodyMedium)
                // Always tappable: shows the quantity, or a hint to add one.
                val hasQuantity = !entry.quantity.isNullOrBlank()
                Text(
                    if (hasQuantity) entry.quantity!! else "Add quantity",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasQuantity) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onQuantityClick).padding(vertical = 4.dp)
                )
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

/** Quantity is free text ("2 dozen", "a bag", "500g"), same as on the web. */
@Composable
private fun QuantityDialog(
    itemName: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quantity for $itemName") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text("e.g. 2 dozen, a bag, 500g") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Search-first add field (see design discussion): type to search your
 * existing items and tap one to add it; a placeholder, a USDA lookup and
 * a plain new item are the fallbacks when nothing fits. Same shape as the
 * web app's add field. */
@Composable
private fun AddToListSection(state: GroceryListUiState, viewModel: GroceryListViewModel, onFieldFocused: () -> Unit) {
    val query = state.newPlaceholderInput
    val trimmed = query.trim()
    val results = state.addSearchResults.take(5)

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.updateNewPlaceholderInput(it) },
            placeholder = { Text("Search your items to add...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFieldFocused() }
        )
        if (trimmed.isNotEmpty()) {
            if (state.isSearchingAdd && results.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }
            results.forEach { item ->
                Surface(
                    onClick = { viewModel.addExistingItem(item) },
                    enabled = !state.isAddingEntry,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (item.brand.isNullOrBlank()) item.name else "${item.name} (${item.brand})",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp)
                    )
                }
                HorizontalDivider()
            }
            if (!state.isSearchingAdd && results.isEmpty()) {
                Text(
                    "No matches",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            TextButton(onClick = { viewModel.addPlaceholder() }, enabled = !state.isAddingPlaceholder) {
                Text(if (state.isAddingPlaceholder) "Adding..." else "Add \"$trimmed\" as a placeholder")
            }
            TextButton(onClick = { viewModel.openAddItemFlow(searchUsda = true) }) {
                Text("Search USDA for \"$trimmed\"")
            }
            TextButton(onClick = { viewModel.openAddItemFlow(searchUsda = false) }) {
                Text("Create a new item")
            }
        }
    }
}

/** Full-screen host for the existing AddItemScreen, embedded the same
 * way CreateRecipeScreen and MealDetailScreen already embed it: its own
 * keyed ViewModel, reset when the flow ends so the next open starts
 * fresh, and onUseCreatedItem for what to do with the item it makes.
 * Never attached to a meal, so it only creates the item. */
@Composable
private fun AddItemFlowOverlay(
    request: AddItemFlowRequest,
    onUseItem: (Item) -> Unit,
    onClose: () -> Unit
) {
    val addItemViewModel: AddItemViewModel = viewModel(key = "grocery_add_item")

    LaunchedEffect(request) {
        request.usdaQuery?.let { addItemViewModel.jumpToUsdaSearch(it) }
    }

    fun finish() {
        addItemViewModel.resetToScanChoice()
        onClose()
    }

    BackHandler { finish() }

    Surface(modifier = Modifier.fillMaxSize()) {
        AddItemScreen(
            viewModel = addItemViewModel,
            savedScreenPromptText = "Want to add it to your grocery list?",
            savedScreenActionLabel = "Add to grocery list",
            onUseCreatedItem = onUseItem,
            onBack = { finish() },
            onDone = { finish() }
        )
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