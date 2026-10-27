package com.xcloak.qrdine.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

class MenuRepository(private val sb: SupabaseClient, private val restaurantId: String) {

    suspend fun categories(): List<Category> =
        sb.from("categories").select().decodeList<Category>().filter { it.deletedAt == null }.sortedWith(compareBy({ it.sort }, { it.name }))

    suspend fun dishes(): List<Dish> =
        sb.from("dishes").select().decodeList<Dish>()
            .filter { it.deletedAt == null }
            .sortedWith(compareBy({ it.sort }, { it.name.lowercase() }))

    suspend fun variants(): List<DishVariant> = sb.from("dish_variants").select().decodeList<DishVariant>().sortedBy { it.price }
    suspend fun groups(): List<AddonGroup> = sb.from("addon_groups").select().decodeList<AddonGroup>().sortedBy { it.name.lowercase() }
    suspend fun addons(): List<Addon> = sb.from("addons").select().decodeList<Addon>().sortedBy { it.name.lowercase() }
    suspend fun links(): List<DishAddonLink> = sb.from("dish_addon_groups").select().decodeList<DishAddonLink>()
    suspend fun offers(): List<Offer> = sb.from("offers").select().decodeList<Offer>().sortedBy { it.name.lowercase() }

    suspend fun addCategory(name: String, kind: String, sort: Int) {
        sb.from("categories").insert(NewCategory(restaurantId, name.trim(), kind, sort))
    }

    suspend fun updateCategory(id: String, name: String, i18n: Map<String, String>?, from: String?, to: String?) {
        sb.from("categories").update({
            set("name", name.trim())
            set("name_i18n", i18n)
            set("avail_from", from)
            set("avail_to", to)
        }) { filter { eq("id", id) } }
    }

    suspend fun deleteCategory(id: String) {
        sb.postgrest.rpc("delete_category", buildJsonObject { put("p_id", id) })
    }

    /** Writes the new order as sort numbers (the first id gets 0). */
    suspend fun reorderCategories(ids: List<String>) {
        ids.forEachIndexed { i, id -> sb.from("categories").update({ set("sort", i) }) { filter { eq("id", id) } } }
    }

    suspend fun reorderDishes(ids: List<String>) {
        ids.forEachIndexed { i, id -> sb.from("dishes").update({ set("sort", i) }) { filter { eq("id", id) } } }
    }

    suspend fun addDish(form: DishForm, imageUrl: String?): String =
        sb.from("dishes").insert(
            NewDish(
                restaurantId = restaurantId, categoryId = form.categoryId, name = form.name.trim(),
                description = form.description.trim().ifBlank { null }, imageUrl = imageUrl, basePrice = form.pricePaise,
                isVeg = form.isVeg, stockQty = form.stockQty, nameI18n = form.nameI18n, descriptionI18n = form.descI18n,
                originalPrice = form.originalPricePaise,
            )
        ) { select() }.decodeSingle<Dish>().id

    suspend fun updateDish(id: String, form: DishForm, imageUrl: String?) {
        sb.from("dishes").update({
            set("category_id", form.categoryId)
            set("name", form.name.trim())
            set("description", form.description.trim().ifBlank { null })
            set("image_url", imageUrl)
            set("base_price", form.pricePaise)
            set("original_price", form.originalPricePaise)
            set("is_veg", form.isVeg)
            set("stock_qty", form.stockQty)
            set("name_i18n", form.nameI18n)
            set("description_i18n", form.descI18n)
        }) { filter { eq("id", id) } }
    }

    suspend fun setAvailable(id: String, available: Boolean) {
        sb.from("dishes").update({ set("is_available", available) }) { filter { eq("id", id) } }
    }

    /** Soft delete so old bills keep their dish references. */
    suspend fun deleteDish(id: String) {
        sb.from("dishes").update({ set("deleted_at", java.time.Instant.now().toString()) }) { filter { eq("id", id) } }
    }

    /** Replaces all variants of a dish (name, price in paise). */
    suspend fun saveVariants(dishId: String, variants: List<Triple<String, Int, Int?>>) {
        sb.from("dish_variants").delete { filter { eq("dish_id", dishId) } }
        if (variants.isNotEmpty()) sb.from("dish_variants").insert(variants.map { (n, p, original) -> NewVariant(restaurantId, dishId, n.trim(), p, original) })
    }

    suspend fun setDishGroups(dishId: String, groupIds: Set<String>) {
        sb.from("dish_addon_groups").delete { filter { eq("dish_id", dishId) } }
        if (groupIds.isNotEmpty()) sb.from("dish_addon_groups").insert(groupIds.map { NewLink(restaurantId, dishId, it) })
    }

    suspend fun addGroup(name: String, min: Int, max: Int) {
        sb.from("addon_groups").insert(NewGroup(restaurantId, name.trim(), min, max))
    }

    suspend fun updateGroup(id: String, name: String, min: Int, max: Int) {
        sb.from("addon_groups").update({ set("name", name.trim()); set("min_select", min); set("max_select", max) }) { filter { eq("id", id) } }
    }

    suspend fun deleteGroup(id: String) { sb.from("addon_groups").delete { filter { eq("id", id) } } }

    suspend fun addAddon(groupId: String, name: String, pricePaise: Int) {
        sb.from("addons").insert(NewAddon(restaurantId, groupId, name.trim(), pricePaise))
    }

    suspend fun setAddonAvailable(id: String, available: Boolean) {
        sb.from("addons").update({ set("is_available", available) }) { filter { eq("id", id) } }
    }

    suspend fun deleteAddon(id: String) { sb.from("addons").delete { filter { eq("id", id) } } }

    suspend fun addOffer(name: String, buyQty: Int, getQty: Int, buyDishId: String, rewardDishId: String) {
        sb.from("offers").insert(NewOffer(restaurantId, name.trim(), buyQty, getQty, buyDishId, rewardDishId))
    }

    suspend fun setOfferActive(id: String, active: Boolean) {
        sb.from("offers").update({ set("active", active) }) { filter { eq("id", id) } }
    }

    suspend fun deleteOffer(id: String) { sb.from("offers").delete { filter { eq("id", id) } } }

    /** Creates missing categories (by name), dishes, and size rows. Returns dishes added. */
    suspend fun importDishes(rows: List<CsvDish>): Int {
        val existing = categories().associateBy { it.name.trim().lowercase() }.toMutableMap()
        val categoryData = rows.groupBy { it.category.trim().lowercase() }.mapValues { (_, categoryRows) ->
            require(categoryRows.map { it.category.trim() }.distinct().size == 1) { "CSV category spellings must match exactly." }
            val kinds = categoryRows.mapNotNull { it.categoryKind }.distinct()
            require(kinds.size <= 1) { "Category rows specify conflicting category_kind values." }
            val translations = categoryRows.flatMap { it.categoryI18n.entries }
                .groupBy({ it.key }, { it.value }).mapValues { (_, values) ->
                    require(values.distinct().size == 1) { "Category rows specify conflicting translated names." }
                    values.first()
                }
            kinds.singleOrNull() to translations
        }
        val missing = rows.filter { it.category.trim().isNotBlank() && it.category.trim().lowercase() !in existing }
            .distinctBy { it.category.trim().lowercase() }
        if (missing.isNotEmpty()) {
            val base = existing.size
            val created = sb.from("categories").insert(missing.mapIndexed { i, row ->
                val (kind, translations) = categoryData.getValue(row.category.trim().lowercase())
                NewCategory(restaurantId, row.category.trim(), kind ?: "food", base + i, translations.takeIf { it.isNotEmpty() })
            }) { select() }.decodeList<Category>()
            created.forEach { existing[it.name.trim().lowercase()] = it }
        }
        categoryData.forEach { (key, info) ->
            val (kind, translations) = info
            if (translations.isNotEmpty() || kind != null) existing[key]?.let { category ->
                sb.from("categories").update({
                    if (translations.isNotEmpty()) set("name_i18n", category.nameI18n.orEmpty() + translations)
                    kind?.let { set("kind", it) }
                }) { filter { eq("id", category.id) } }
            }
        }
        val variantsByDish = rows.filter { !it.variantName.isNullOrBlank() }
            .groupBy { "${it.category.trim().lowercase()}\u001f${it.name.trim().lowercase()}" }
        val plainRows = rows.filter { it.variantName.isNullOrBlank() }
        require(plainRows.none { "${it.category.trim().lowercase()}\u001f${it.name.trim().lowercase()}" in variantsByDish }) {
            "A dish cannot mix a plain row with variant rows."
        }
        val dishRows = plainRows.map { listOf(it) } + variantsByDish.values
        dishRows.forEach { group ->
            require(group.map { it.category.trim() }.distinct().size == 1 && group.map { it.name.trim() }.distinct().size == 1 &&
                group.map { it.categoryI18n }.distinct().size == 1 && group.map { it.categoryKind }.distinct().size == 1 &&
                group.map { listOf(it.pricePaise, it.originalPricePaise, it.isVeg, it.description, it.nameI18n, it.descriptionI18n) }.distinct().size == 1) {
                "Repeated dish rows must repeat the same category, names, descriptions, selling price, original price and vegetarian status."
            }
            require(group.filter { !it.variantName.isNullOrBlank() }.map { it.variantName!!.trim().lowercase() }.distinct().size == group.count { !it.variantName.isNullOrBlank() }) {
                "Variant names must be unique within a dish."
            }
            group
        }
        val dishes = dishRows.map { group ->
            val r = group.first()
            val cat = existing[r.category.trim().lowercase()] ?: error("Missing category: ${r.category}")
            NewDish(
                restaurantId, cat.id, r.name.trim(), r.description.trim().ifBlank { null }, null, r.pricePaise, r.isVeg,
                nameI18n = r.nameI18n.takeIf { it.isNotEmpty() },
                descriptionI18n = r.descriptionI18n.takeIf { it.isNotEmpty() },
                originalPrice = r.originalPricePaise,
            )
        }
        val createdDishes = if (dishes.isNotEmpty()) {
            sb.from("dishes").insert(dishes) { select(Columns.list("id,category_id,name")) }.decodeList<ImportedDishRef>()
        } else emptyList()
        val variantRows = dishRows.flatMapIndexed { index, group ->
            group.filter { !it.variantName.isNullOrBlank() }.map { Triple(index, it.variantName!!.trim(), it.variantPricePaise!! to it.variantOriginalPricePaise) }
        }
        if (variantRows.isNotEmpty()) {
            val idByKey = createdDishes.associateBy { "${it.categoryId}\u001f${it.name.trim().lowercase()}" }
            variantRows.groupBy { it.first }.forEach { (index, variants) ->
                val row = dishRows[index].first()
                val category = existing.getValue(row.category.trim().lowercase())
                val dishId = idByKey["${category.id}\u001f${row.name.trim().lowercase()}"]?.id
                    ?: error("Imported dish not found: ${row.name}")
                saveVariants(dishId, variants.map { (_, name, priceAndOriginal) ->
                    Triple(name, priceAndOriginal.first, priceAndOriginal.second)
                })
            }
        }
        return dishes.size
    }

    @kotlinx.serialization.Serializable
    private data class ImportedDishRef(@kotlinx.serialization.SerialName("category_id") val categoryId: String, val name: String, val id: String)

    /** Uploads to dish-images/<restaurant_id>/<uuid>.jpg (storage policy only allows your own folder). */
    suspend fun uploadImage(bytes: ByteArray): String {
        val bucket = sb.storage.from("dish-images")
        val path = "$restaurantId/${UUID.randomUUID()}.jpg"
        bucket.upload(path, bytes)
        return bucket.publicUrl(path)
    }
}
