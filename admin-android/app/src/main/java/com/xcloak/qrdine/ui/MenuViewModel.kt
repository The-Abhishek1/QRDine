package com.xcloak.qrdine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xcloak.qrdine.Diagnostics
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MenuUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val restaurantName: String = "",
    val categories: List<Category> = emptyList(),
    val dishes: List<Dish> = emptyList(),
    val variants: List<DishVariant> = emptyList(),
    val groups: List<AddonGroup> = emptyList(),
    val addons: List<Addon> = emptyList(),
    val links: List<DishAddonLink> = emptyList(),
    val offers: List<Offer> = emptyList(),
    val languages: List<String> = emptyList(),
    val message: String? = null,
    val error: String? = null,
)

private fun splitCsv(line: String): List<String> {
    val out = mutableListOf<String>(); val cur = StringBuilder(); var quoted = false; var i = 0
    while (i < line.length) {
        val ch = line[i]
        when {
            ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
            ch == '"' -> quoted = !quoted
            ch == ',' && !quoted -> { out.add(cur.toString().trim()); cur.clear() }
            else -> cur.append(ch)
        }
        i++
    }
    out.add(cur.toString().trim())
    return out
}

/** category,name,price,veg(y/n),description. A header row is skipped because its price is not a number. */
fun parseDishCsv(text: String): List<CsvDish> =
    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { l ->
        val c = splitCsv(l)
        if (c.size < 3) return@mapNotNull null
        val price = rupeesToPaise(c[2].replace("₹", "").replace(",", "")) ?: return@mapNotNull null
        if (c[0].isBlank() || c[1].isBlank() || price > 10_000_000L) return@mapNotNull null
        val veg = c.getOrNull(3)?.lowercase()?.let { it !in setOf("n", "no", "non-veg", "nonveg", "non veg", "false", "0") } ?: true
        CsvDish(c[0], c[1], price.toInt(), veg, c.getOrNull(4).orEmpty())
    }.toList()

class MenuViewModel(private val restaurantId: String) : ViewModel() {
    private val sb = QrDineApp.container.supabase
    private val repo = MenuRepository(sb, restaurantId)
    private val owner = QrDineApp.container.owner

    private val _state = MutableStateFlow(MenuUiState())
    val state: StateFlow<MenuUiState> = _state

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            try {
                val rest = runCatching { owner.restaurant(restaurantId) }.getOrNull()
                val categories = repo.categories()
                val dishes = repo.dishes()
                val variants = repo.variants()
                val groups = repo.groups()
                val addons = repo.addons()
                val links = repo.links()
                val offersResult = runCatching { repo.offers() }
                _state.update {
                    it.copy(
                        loading = false,
                        error = offersResult.exceptionOrNull()?.let(::offerLoadError),
                        restaurantName = rest?.name ?: it.restaurantName,
                        languages = rest?.languages ?: it.languages,
                        categories = categories,
                        dishes = dishes,
                        variants = variants,
                        groups = groups,
                        addons = addons,
                        links = links,
                        offers = offersResult.getOrDefault(emptyList()),
                    )
                }
            } catch (e: Exception) {
                Diagnostics.record(QrDineApp.appContext, e, "menu")
                _state.update { it.copy(loading = false, error = "Could not load the menu. Check your connection and refresh.") }
            }
        }
    }

    private fun offerLoadError(e: Throwable): String {
        val message = e.message.orEmpty()
        return if ("public.offers" in message && ("schema cache" in message || "does not exist" in message)) {
            "Offers are unavailable until the latest backend migration is applied. Menu items are still available."
        } else {
            "Could not load offers. Check the backend setup and refresh."
        }
    }

    private fun friendly(e: Exception): String {
        val m = e.message ?: return "Something went wrong"
        return when {
            "category_has_dishes" in m -> "Delete or move the dishes in this category first."
            "category_not_found" in m -> "That category no longer exists."
            "violates foreign key" in m -> "That is still in use. Remove it from the dishes first."
            else -> "Could not save the menu change. Check your connection and try again."
        }
    }

    private fun run(onDone: () -> Unit = {}, block: suspend () -> Unit) {
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                _state.update { it.copy(saving = false) }
                refresh()
                onDone()
            } catch (e: Exception) {
                Diagnostics.record(QrDineApp.appContext, e, "menu")
                _state.update { it.copy(saving = false, error = friendly(e)) }
            }
        }
    }

    fun addCategory(name: String, kind: String, i18n: Map<String, String>?, from: String?, to: String?) = run {
        repo.addCategory(name, kind, _state.value.categories.size)
        if (i18n != null || from != null) {
            val created = repo.categories().lastOrNull { it.name == name.trim() }
            if (created != null) repo.updateCategory(created.id, name, i18n, from, to)
        }
    }
    fun updateCategory(c: Category, name: String, i18n: Map<String, String>?, from: String?, to: String?) = run { repo.updateCategory(c.id, name, i18n, from, to) }
    fun deleteCategory(id: String) = run { repo.deleteCategory(id) }

    fun moveCategory(c: Category, delta: Int) {
        val list = _state.value.categories.map { it.id }.toMutableList()
        val i = list.indexOf(c.id); val j = i + delta
        if (i < 0 || j !in list.indices) return
        list[i] = list[j].also { list[j] = list[i] }
        run { repo.reorderCategories(list) }
    }

    fun moveDish(d: Dish, delta: Int) {
        val list = _state.value.dishes.filter { it.categoryId == d.categoryId }.map { it.id }.toMutableList()
        val i = list.indexOf(d.id); val j = i + delta
        if (i < 0 || j !in list.indices) return
        list[i] = list[j].also { list[j] = list[i] }
        run { repo.reorderDishes(list) }
    }

    fun toggleAvailable(dish: Dish) {
        _state.update { s -> s.copy(dishes = s.dishes.map { if (it.id == dish.id) it.copy(isAvailable = !dish.isAvailable) else it }) }
        viewModelScope.launch {
            try { repo.setAvailable(dish.id, !dish.isAvailable) }
            catch (e: Exception) {
                Diagnostics.record(QrDineApp.appContext, e, "menu")
                _state.update { it.copy(error = friendly(e)) }
                refresh()
            }
        }
    }

    /** existing == null creates a new dish. imageBytes == null keeps the current image. */
    fun saveDish(existing: Dish?, form: DishForm, imageBytes: ByteArray?, variants: List<Pair<String, Int>>, groupIds: Set<String>, onDone: () -> Unit) = run(onDone) {
        val url = if (imageBytes != null) repo.uploadImage(imageBytes) else existing?.imageUrl
        val id = if (existing == null) repo.addDish(form, url) else { repo.updateDish(existing.id, form, url); existing.id }
        repo.saveVariants(id, variants)
        repo.setDishGroups(id, groupIds)
    }

    fun deleteDish(dish: Dish, onDone: () -> Unit) = run(onDone) { repo.deleteDish(dish.id) }

    fun importCsv(text: String) {
        val rows = parseDishCsv(text)
        if (rows.isEmpty()) { _state.update { it.copy(error = "No dishes found. Use: category, name, price, veg (y/n), description.") }; return }
        run { val n = repo.importDishes(rows); _state.update { it.copy(message = "Imported $n dishes") } }
    }

    fun reportCsvReadError(e: Exception) {
        Diagnostics.record(QrDineApp.appContext, e, "menu")
        _state.update { it.copy(error = "We couldn't read that file. Choose a CSV file and try again.") }
    }

    fun addGroup(name: String, min: Int, max: Int) = run { repo.addGroup(name, min, max) }
    fun updateGroup(g: AddonGroup, name: String, min: Int, max: Int) = run { repo.updateGroup(g.id, name, min, max) }
    fun deleteGroup(g: AddonGroup) = run { repo.deleteGroup(g.id) }
    fun addAddon(groupId: String, name: String, pricePaise: Int) = run { repo.addAddon(groupId, name, pricePaise) }
    fun setAddonAvailable(a: Addon, available: Boolean) = run { repo.setAddonAvailable(a.id, available) }
    fun deleteAddon(a: Addon) = run { repo.deleteAddon(a.id) }
    fun addOffer(name: String, buyQty: Int, getQty: Int, buyDishId: String, rewardDishId: String, onDone: () -> Unit = {}) =
        run(onDone) { repo.addOffer(name, buyQty, getQty, buyDishId, rewardDishId) }
    fun setOfferActive(offer: Offer, active: Boolean) = run { repo.setOfferActive(offer.id, active) }
    fun deleteOffer(offer: Offer) = run { repo.deleteOffer(offer.id) }

    fun clearError() = _state.update { it.copy(error = null) }
    fun messageShown() = _state.update { it.copy(message = null) }

    class Factory(private val restaurantId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MenuViewModel(restaurantId) as T
    }
}
