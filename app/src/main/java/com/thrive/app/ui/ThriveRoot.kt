package com.thrive.app.ui

import android.app.Application
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Kitchen
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.RestaurantMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import com.thrive.app.update.UpdateBus
import com.thrive.app.update.UpdateCheckWorker
import com.thrive.app.update.UpdateDialog
import com.thrive.app.update.startUpdateDownload
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.thrive.app.data.ThriveRepository
import com.thrive.app.ui.budget.BudgetScreen
import com.thrive.app.ui.budget.BudgetViewModel
import com.thrive.app.ui.pantry.MealStepsScreen
import com.thrive.app.ui.pantry.PantryScreen
import com.thrive.app.ui.pantry.WeeklyPlanScreen
import com.thrive.app.ui.pantry.PantryViewModel
import com.thrive.app.ui.recipes.RecipeDetailScreen
import com.thrive.app.ui.recipes.RecipesScreen
import com.thrive.app.ui.recipes.RecipesViewModel
import com.thrive.app.ui.savings.CouponDetailScreen
import com.thrive.app.ui.savings.SavingsScreen
import com.thrive.app.ui.savings.SavingsViewModel
import com.thrive.app.ui.settings.SettingsScreen
import com.thrive.app.ui.theme.LocalThriveColors
import com.thrive.app.ui.theme.ThriveFont

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("savings", "Savings", Icons.Rounded.LocalOffer),
    Tab("recipes", "Recipes", Icons.Rounded.RestaurantMenu),
    Tab("pantry", "Pantry", Icons.Rounded.Kitchen),
    Tab("budget", "Budget", Icons.Rounded.AccountBalanceWallet),
)

@Composable
fun ThriveRoot() {
    val app = LocalContext.current.applicationContext as com.thrive.app.ThriveApp
    val repo = remember { ThriveRepository(app, app.settings) }
    val nav = rememberNavController()

    // Non-blocking initial sync: the bundled feed stays visible until the
    // server responds, and nothing breaks if it can't be reached.
    LaunchedEffect(repo) { repo.syncNow(force = false) }

    val savingsVm: SavingsViewModel = viewModel(factory = viewModelFactory { initializer { SavingsViewModel(app, repo) } })
    val recipesVm: RecipesViewModel = viewModel(factory = viewModelFactory { initializer { RecipesViewModel(repo) } })
    val pantryVm: PantryViewModel = viewModel(factory = viewModelFactory { initializer { PantryViewModel(app, repo) } })
    val budgetVm: BudgetViewModel = viewModel(factory = viewModelFactory { initializer { BudgetViewModel(app, repo) } })

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showTabs = tabs.any { it.route == currentRoute }

    // In-app update popup: the GitHub check (launch + every 15 min, no sync
    // server or API key needed) publishes here. "Later" remembers the version
    // so the app stops asking until a newer release exists.
    val update by UpdateBus.updates.collectAsState()
    val dismissedVersion = app.settings.getString(UpdateCheckWorker.KEY_DISMISSED_VERSION, null)
    update?.let { pending ->
        if (pending.versionName != dismissedVersion) {
            UpdateDialog(
                update = pending,
                onDismiss = {
                    app.settings.putString(UpdateCheckWorker.KEY_DISMISSED_VERSION, pending.versionName)
                    UpdateBus.clear()
                },
                onUpdateNow = {
                    startUpdateDownload(app, pending)
                    UpdateBus.clear()
                },
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showTabs) {
                ThriveBottomBar(currentRoute, onSelect = { route ->
                    nav.navigate(route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "savings",
            modifier = Modifier.padding(padding),
        ) {
            composable("savings") { SavingsScreen(savingsVm, onOpenCoupon = { id -> nav.navigate("coupon/$id") }, onOpenSettings = { nav.navigate("settings") }) }
            composable("recipes") { RecipesScreen(recipesVm, onOpenRecipe = { id -> nav.navigate("recipe/$id") }) }
            composable("pantry") {
                PantryScreen(
                    pantryVm,
                    onOpenMeal = { index -> nav.navigate("meal/$index") },
                    onOpenWeekPlan = { nav.navigate("weeklyplan") },
                )
            }
            composable("budget") { BudgetScreen(budgetVm, onOpenSettings = { nav.navigate("settings") }) }
            composable("settings") { SettingsScreen(savingsVm, onBack = { nav.popBackStack() }) }
            composable("coupon/{couponId}") { entry ->
                val id = entry.arguments?.getString("couponId") ?: ""
                CouponDetailScreen(
                    vm = savingsVm,
                    couponId = id,
                    onBack = { nav.popBackStack() },
                    onOpenCoupon = { newId ->
                        nav.navigate("coupon/$newId") {
                            popUpTo("coupon/$id") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable("recipe/{recipeId}") { entry ->
                val id = entry.arguments?.getString("recipeId") ?: ""
                RecipeDetailScreen(
                    recipesVm,
                    budgetVm,
                    id,
                    onBack = { nav.popBackStack() },
                    onAddToShoppingList = { nav.navigate("budget") { popUpTo("budget") { inclusive = false } } },
                )
            }
            composable("meal/{index}") { entry ->
                val index = entry.arguments?.getString("index")?.toIntOrNull() ?: 0
                MealStepsScreen(pantryVm, budgetVm, index, onBack = { nav.popBackStack() })
            }
            composable("weeklyplan") {
                WeeklyPlanScreen(pantryVm, budgetVm, onBack = { nav.popBackStack() })
            }
        }
    }
}

@Composable
private fun ThriveBottomBar(currentRoute: String?, onSelect: (String) -> Unit) {
    val accents = LocalThriveColors.current
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = androidx.compose.ui.unit.Dp(0f),
    ) {
        tabs.forEach { tab ->
            val selected = currentRoute == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab.route) },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        modifier = Modifier,
                    )
                },
                label = {
                    Text(
                        text = tab.label,
                        fontFamily = ThriveFont,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = accents.deal,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = accents.dealSoft.copy(alpha = 0.55f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
