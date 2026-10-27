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

private fun readCsvRecords(text: String): List<List<String>> {
    val records = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var closedQuote = false
    var i = 0
    fun endCell() { row.add(cell.toString()); cell.clear(); closedQuote = false }
    fun endRow() {
        endCell()
        if (row.any { it.isNotBlank() }) records.add(row)
        row = mutableListOf()
    }
    while (i < text.length) {
        val ch = text[i]
        if (quoted) {
            when {
                ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                ch == '"' -> { quoted = false; closedQuote = true }
                else -> cell.append(ch)
            }
        } else {
            when {
                ch == '"' && cell.isEmpty() && !closedQuote -> quoted = true
                ch == ',' -> endCell()
                ch == '\n' -> endRow()
                ch == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> { endRow(); i++ }
                ch == '\r' -> endRow()
                closedQuote && !ch.isWhitespace() -> throw IllegalArgumentException("Invalid character after a quoted CSV field.")
                ch == '"' -> throw IllegalArgumentException("A quote inside a CSV field must be doubled.")
                else -> cell.append(ch)
            }
        }
        i++
    }
    if (quoted) throw IllegalArgumentException("A quoted CSV field was not closed.")
    if (row.isNotEmpty() || cell.isNotEmpty() || closedQuote) endRow()
    return records
}

private fun List<String>.textAt(headers: Map<String, Int>, name: String): String =
    getOrNull(headers[name] ?: -1).orEmpty().trim().removePrefix("\uFEFF")

private fun parseCsvPrice(raw: String): Int? =
    rupeesToPaise(raw.replace("₹", "").replace(",", "").trim())?.takeIf { it <= 10_000_000L }?.toInt()

/** Reads the current language-aware CSV shape and the original five-column import format. */
fun parseDishCsv(text: String, supportedLanguages: List<String> = LANG_NAMES.keys.toList()): List<CsvDish> {
    val records = readCsvRecords(text)
    if (records.isEmpty()) return emptyList()
    val first = records.first().map { it.trim().removePrefix("\uFEFF").lowercase() }
    val hasHeader = "category" in first && "name" in first && "price" in first
    val headers = if (hasHeader) first.withIndex().associate { it.value to it.index } else emptyMap()
    if (hasHeader && headers.size != first.size) throw IllegalArgumentException("CSV header names must be unique.")
    fun localizedCode(key: String): String? =
        listOf("category_", "name_", "description_").firstNotNullOfOrNull { prefix ->
            key.removePrefix(prefix).takeIf { key.startsWith(prefix) && it.matches(Regex("[a-z]{2,3}")) }
        }
    if (hasHeader) {
        val allowed = (menuCsvHeaders(supportedLanguages) + listOf("category", "name", "price", "veg", "description")).toSet()
        val unsupportedColumns = headers.keys.filterNot { it in allowed || localizedCode(it) != null }
        if (unsupportedColumns.isNotEmpty()) throw IllegalArgumentException(
            "CSV columns are not supported: ${unsupportedColumns.joinToString()}. Add-on groups and other menu settings must be managed separately.",
        )
    }
    val localeColumns = headers.keys.mapNotNull(::localizedCode).distinct()
    val unsupported = localeColumns.filterNot { it in supportedLanguages }
    if (unsupported.isNotEmpty()) throw IllegalArgumentException(
        "CSV language columns are not enabled for this restaurant: ${unsupported.joinToString()}. Enable them in Settings or use the downloaded template.",
    )
    val data = if (hasHeader) records.drop(1) else records
    if (!hasHeader && data.any { it.size > 5 }) throw IllegalArgumentException(
        "Headerless CSV supports only category,name,price,veg,description. Use the template for variants and original prices.",
    )
    return data.mapIndexedNotNull { idx, row ->
        val values = if (hasHeader) row else row
        if (hasHeader && values.size > first.size) throw IllegalArgumentException("CSV row ${idx + 2} has more cells than the header.")
        fun value(key: String, legacyIndex: Int? = null) =
            if (hasHeader) values.textAt(headers, key) else legacyIndex?.let { values.getOrNull(it).orEmpty().trim() }.orEmpty()
        val category = value("category", 0)
        val categoryKind = value("category_kind").takeIf { it.isNotBlank() }?.lowercase()
        if (categoryKind != null && categoryKind !in setOf("food", "beverage")) {
            throw IllegalArgumentException("CSV row ${idx + if (hasHeader) 2 else 1} has an invalid category_kind; use food or beverage.")
        }
        val name = value("name", 1)
        val rawPrice = value("price", 2)
        if (category.isBlank() || name.isBlank() || rawPrice.isBlank()) {
            throw IllegalArgumentException("CSV row ${idx + if (hasHeader) 2 else 1} needs category, name and price.")
        }
        val price = parseCsvPrice(rawPrice)
            ?: throw IllegalArgumentException("CSV row ${idx + if (hasHeader) 2 else 1} has an invalid selling price.")
        val originalRaw = value("original_price")
        val original = originalRaw.takeIf { it.isNotBlank() }?.let(::parseCsvPrice)
        if (originalRaw.isNotBlank() && (original == null || original <= price)) {
            throw IllegalArgumentException("CSV row ${idx + if (hasHeader) 2 else 1}: original_price must be greater than price.")
        }
        val vegRaw = value("veg", 3).lowercase()
        val veg = when (vegRaw) {
            "", "y", "yes", "veg", "true", "1" -> true
            "n", "no", "non-veg", "nonveg", "non veg", "false", "0" -> false
            else -> throw IllegalArgumentException("CSV row ${idx + if (hasHeader) 2 else 1} has an invalid veg value; use y or n.")
        }
        val description = value("description", 4)
        val categoryI18n = headers.keys.mapNotNull { key ->
            localizedCode(key)?.takeIf { key.startsWith("category_") && value(key).isNotBlank() }?.let { it to value(key) }
        }.toMap()
        val nameI18n = headers.keys.mapNotNull { key ->
            localizedCode(key)?.takeIf { key.startsWith("name_") && value(key).isNotBlank() }?.let { it to value(key) }
        }.toMap()
        val descriptionI18n = headers.keys.mapNotNull { key ->
            localizedCode(key)?.takeIf { key.startsWith("description_") && value(key).isNotBlank() }?.let { it to value(key) }
        }.toMap()
        val variantName = value("variant_name").takeIf { it.isNotBlank() }
        val variantPriceRaw = value("variant_price")
        val variantOriginalRaw = value("variant_original_price")
        if ((variantName == null) != variantPriceRaw.isBlank()) {
            throw IllegalArgumentException("CSV row ${idx + if (hasHeader) 2 else 1} needs both variant_name and variant_price.")
        }
        val variantPrice = variantPriceRaw.takeIf { it.isNotBlank() }?.let(::parseCsvPrice)
        if (variantPriceRaw.isNotBlank() && variantPrice == null) {
            throw IllegalArgumentException("CSV row ${idx + if (hasHeader) 2 else 1} has an invalid variant price.")
        }
        val variantOriginal = variantOriginalRaw.takeIf { it.isNotBlank() }?.let(::parseCsvPrice)
        if (variantOriginalRaw.isNotBlank() && (variantOriginal == null || variantPrice == null || variantOriginal <= variantPrice)) {
            throw IllegalArgumentException("CSV row ${idx + if (hasHeader) 2 else 1}: variant_original_price must be greater than variant_price.")
        }
        CsvDish(category, categoryKind, categoryI18n, name, nameI18n, price, original, veg, description, descriptionI18n, variantName, variantPrice, variantOriginal)
    }
}

fun menuCsvHeaders(languages: List<String>): List<String> =
    listOf("category", "category_kind") + languages.map { "category_$it" } +
        listOf("name") + languages.map { "name_$it" } +
        listOf("price", "original_price", "veg", "description") +
        languages.map { "description_$it" } +
        listOf("variant_name", "variant_price", "variant_original_price")

private fun csvCell(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || value != value.trim())
        "\"${value.replace("\"", "\"\"")}\"" else value

private fun paiseAsRupees(paise: Int?): String =
    paise?.let { "%d.%02d".format(java.util.Locale.ROOT, it / 100, it % 100).trimEnd('0').trimEnd('.') }.orEmpty()

fun menuCsvTemplate(languages: List<String>): String {
    val headers = menuCsvHeaders(languages)
    fun row(variant: String, amount: String, original: String): String {
        val data = headers.associateWith { "" }.toMutableMap()
        data["category"] = "Mains"
        data["category_kind"] = "food"
        data["name"] = "Paneer Tikka"
        data["price"] = "250"
        data["original_price"] = "300"
        data["veg"] = "y"
        data["description"] = "Smoky, grilled paneer"
        data["variant_name"] = variant
        data["variant_price"] = amount
        data["variant_original_price"] = original
        return headers.joinToString(",") { csvCell(data[it].orEmpty()) }
    }
    return (listOf(headers.joinToString(",") { csvCell(it) }, row("Half", "140", "180"), row("Full", "250", "300"))).joinToString("\r\n", postfix = "\r\n")
}

fun exportMenuCsv(state: MenuUiState): String {
    val headers = menuCsvHeaders(state.languages)
    val categories = state.categories.associateBy { it.id }
    val variants = state.variants.groupBy { it.dishId }
    val orderedDishes = state.categories.flatMap { category -> state.dishes.filter { it.categoryId == category.id } } +
        state.dishes.filter { it.categoryId !in categories }
    val lines = orderedDishes.flatMap { dish ->
        val cat = categories[dish.categoryId]
        val sizes: List<DishVariant?> = variants[dish.id].orEmpty().map { it }.ifEmpty { listOf(null) }
        sizes.map { variant ->
            val data = headers.associateWith { "" }.toMutableMap()
            data["category"] = cat?.name.orEmpty()
            data["category_kind"] = cat?.kind.orEmpty()
            cat?.nameI18n.orEmpty().forEach { (lang, value) -> data["category_$lang"] = value }
            data["name"] = dish.name
            dish.nameI18n.orEmpty().forEach { (lang, value) -> data["name_$lang"] = value }
            data["price"] = paiseAsRupees(dish.basePrice)
            data["original_price"] = paiseAsRupees(dish.originalPrice)
            data["veg"] = if (dish.isVeg) "y" else "n"
            data["description"] = dish.description.orEmpty()
            dish.descriptionI18n.orEmpty().forEach { (lang, value) -> data["description_$lang"] = value }
            data["variant_name"] = variant?.name.orEmpty()
            data["variant_price"] = paiseAsRupees(variant?.price)
            data["variant_original_price"] = paiseAsRupees(variant?.originalPrice)
            headers.joinToString(",") { csvCell(data[it].orEmpty()) }
        }
    }
    return (listOf(headers.joinToString(",") { csvCell(it) }) + lines).joinToString("\r\n", postfix = "\r\n")
}

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
            "Repeated dish rows" in m || "A dish cannot mix" in m || "Variant names must be unique" in m -> m
            "original_price" in m || "dishes_original_price_gt_base" in m || "dish_variants_original_price_gt_price" in m ->
                "Original/list prices must be greater than their selling prices."
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
    fun saveDish(existing: Dish?, form: DishForm, imageBytes: ByteArray?, variants: List<Triple<String, Int, Int?>>, groupIds: Set<String>, onDone: () -> Unit) = run(onDone) {
        val url = if (imageBytes != null) repo.uploadImage(imageBytes) else existing?.imageUrl
        val id = if (existing == null) repo.addDish(form, url) else { repo.updateDish(existing.id, form, url); existing.id }
        repo.saveVariants(id, variants)
        repo.setDishGroups(id, groupIds)
    }

    fun deleteDish(dish: Dish, onDone: () -> Unit) = run(onDone) { repo.deleteDish(dish.id) }

    fun importCsv(text: String) {
        val rows = try { parseDishCsv(text, _state.value.languages) } catch (e: IllegalArgumentException) {
            _state.update { it.copy(error = e.message ?: "The CSV format is invalid.") }
            return
        }
        if (rows.isEmpty()) { _state.update { it.copy(error = "No dishes found. Use the downloadable CSV template or the legacy category,name,price,veg,description format.") }; return }
        run { val n = repo.importDishes(rows); _state.update { it.copy(message = "Imported $n dishes and their variants") } }
    }

    fun reportCsvReadError(e: Exception) {
        Diagnostics.record(QrDineApp.appContext, e, "menu")
        _state.update { it.copy(error = "We couldn't read that file. Choose a CSV file and try again.") }
    }

    fun reportCsvSaved(message: String) = _state.update { it.copy(message = message) }

    fun reportCsvWriteError(e: Exception) {
        Diagnostics.record(QrDineApp.appContext, e, "menu")
        _state.update { it.copy(error = "Couldn't save the CSV file. Choose a location with write access and try again.") }
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
