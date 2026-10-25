package com.xcloak.qrdine.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

data class CsvDish(val category: String, val name: String, val pricePaise: Int, val isVeg: Boolean, val description: String)

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
            )
        ) { select() }.decodeSingle<Dish>().id

    suspend fun updateDish(id: String, form: DishForm, imageUrl: String?) {
        sb.from("dishes").update({
            set("category_id", form.categoryId)
            set("name", form.name.trim())
            set("description", form.description.trim().ifBlank { null })
            set("image_url", imageUrl)
            set("base_price", form.pricePaise)
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
    suspend fun saveVariants(dishId: String, variants: List<Pair<String, Int>>) {
        sb.from("dish_variants").delete { filter { eq("dish_id", dishId) } }
        if (variants.isNotEmpty()) sb.from("dish_variants").insert(variants.map { (n, p) -> NewVariant(restaurantId, dishId, n.trim(), p) })
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

    /** Creates missing categories (by name) and all the dishes. Returns how many dishes were added. */
    suspend fun importDishes(rows: List<CsvDish>): Int {
        val existing = categories().associateBy { it.name.trim().lowercase() }.toMutableMap()
        val missing = rows.map { it.category.trim() }.filter { it.isNotBlank() && it.lowercase() !in existing }.distinctBy { it.lowercase() }
        if (missing.isNotEmpty()) {
            val base = existing.size
            val created = sb.from("categories").insert(missing.mapIndexed { i, n -> NewCategory(restaurantId, n, "food", base + i) }) { select() }.decodeList<Category>()
            created.forEach { existing[it.name.trim().lowercase()] = it }
        }
        val dishes = rows.mapNotNull { r ->
            val cat = existing[r.category.trim().lowercase()] ?: return@mapNotNull null
            NewDish(restaurantId, cat.id, r.name.trim(), r.description.trim().ifBlank { null }, null, r.pricePaise, r.isVeg)
        }
        if (dishes.isNotEmpty()) sb.from("dishes").insert(dishes)
        return dishes.size
    }

    /** Uploads to dish-images/<restaurant_id>/<uuid>.jpg (storage policy only allows your own folder). */
    suspend fun uploadImage(bytes: ByteArray): String {
        val bucket = sb.storage.from("dish-images")
        val path = "$restaurantId/${UUID.randomUUID()}.jpg"
        bucket.upload(path, bytes)
        return bucket.publicUrl(path)
    }
}
