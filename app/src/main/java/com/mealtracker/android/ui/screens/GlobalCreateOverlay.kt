package com.mealtracker.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.CreateGroceryEntryRequest
import kotlinx.coroutines.launch

enum class GlobalCreateFlow { ITEM, RECIPE }

/**
 * Full-screen "create a new item / recipe" flow that isn't tied to any
 * meal, reached from the create button on Home, Journal and Recipes.
 * Reuses the same flows as everywhere else (AddItemScreen for items,
 * CreateRecipeContent for recipes) with their own keyed ViewModels, reset
 * when the flow ends so the next open starts fresh.
 */
@Composable
fun GlobalCreateOverlay(flow: GlobalCreateFlow, onClose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        when (flow) {
            GlobalCreateFlow.ITEM -> {
                val addItemViewModel: AddItemViewModel = viewModel(key = "global_add_item")
                val scope = rememberCoroutineScope()

                fun finish() {
                    addItemViewModel.resetToScanChoice()
                    onClose()
                }

                BackHandler { finish() }

                AddItemScreen(
                    viewModel = addItemViewModel,
                    savedScreenPromptText = "Want to add it to your grocery list?",
                    savedScreenActionLabel = "Add to grocery list",
                    onUseCreatedItem = { item ->
                        scope.launch {
                            try {
                                ApiClient.service.addToGroceryList(CreateGroceryEntryRequest(itemId = item.itemId))
                            } catch (e: Exception) {
                                // Best effort: the item itself is already saved.
                            }
                            finish()
                        }
                    },
                    onBack = { finish() },
                    onDone = { finish() }
                )
            }

            GlobalCreateFlow.RECIPE -> {
                val createRecipeViewModel: CreateRecipeViewModel = viewModel(key = "global_create_recipe")

                fun finish() {
                    createRecipeViewModel.reset()
                    onClose()
                }

                BackHandler { finish() }

                CreateRecipeContent(
                    viewModel = createRecipeViewModel,
                    onDone = { finish() }
                )
            }
        }
    }
}
