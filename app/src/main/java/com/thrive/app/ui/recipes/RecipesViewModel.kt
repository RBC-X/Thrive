package com.thrive.app.ui.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thrive.app.data.ThriveRepository
import com.thrive.app.data.model.Recipe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Section(val key: String, val title: String, val subtitle: String)

data class RecipesUiState(
    val recipes: List<Recipe> = emptyList(),
    val favorites: Set<String> = emptySet(),
) {
    val sections: List<Section> = listOf(
        Section("under_10", "Under $10", "Feeds the family for pocket change"),
        Section("under_20", "Under 20 Minutes", "From counter to table, fast"),
        Section("five_ingredients", "5 Ingredients", "Short list, big flavor"),
        Section("family_favorites", "Family Favorites", "The ones they ask for again"),
        Section("one_pot", "One Pot & Done", "Dinner and dishes, finished together"),
    )

    fun forSection(key: String): List<Recipe> = recipes.filter { it.section == key }

    val featured: List<Recipe> get() = recipes.filter { it.featured }

    fun searchResults(query: String): List<Recipe> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        return recipes.filter {
            it.name.lowercase().contains(q) ||
                it.tags.any { t -> t.contains(q) } ||
                it.description.lowercase().contains(q)
        }
    }
}

class RecipesViewModel(private val repo: ThriveRepository) : ViewModel() {

    private val _state = MutableStateFlow(RecipesUiState(recipes = repo.recipes))
    val state: StateFlow<RecipesUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(favorites = repo.favoriteRecipeIds()) }
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch {
            val nowFavorite = repo.toggleRecipeFavorite(id)
            _state.update { s ->
                val favs = s.favorites.toMutableSet()
                if (nowFavorite) favs.add(id) else favs.remove(id)
                s.copy(favorites = favs)
            }
        }
    }
}
