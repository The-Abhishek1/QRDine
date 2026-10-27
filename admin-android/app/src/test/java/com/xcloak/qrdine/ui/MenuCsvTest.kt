package com.xcloak.qrdine.ui

import com.xcloak.qrdine.data.Category
import com.xcloak.qrdine.data.Dish
import com.xcloak.qrdine.data.DishVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MenuCsvTest {
    @Test
    fun template_roundTrips_locales_and_size_price_comparisons() {
        val rows = parseDishCsv(menuCsvTemplate(listOf("hi", "kn")), listOf("hi", "kn"))

        assertEquals(2, rows.size)
        assertEquals("Paneer Tikka", rows.first().name)
        assertEquals("Mains", rows.first().category)
        assertEquals(25_000, rows.first().pricePaise)
        assertEquals(30_000, rows.first().originalPricePaise)
        assertEquals("Half", rows.first().variantName)
        assertEquals(14_000, rows.first().variantPricePaise)
        assertEquals(18_000, rows.first().variantOriginalPricePaise)
        assertEquals(25_000, rows.last().variantPricePaise)
        assertEquals(30_000, rows.last().variantOriginalPricePaise)
    }

    @Test
    fun legacy_quoted_csv_preserves_commas_quotes_and_multiline_fields() {
        val row = parseDishCsv("category,name,price,veg,description\r\nMains,\"Paneer \"\"Special\"\", grilled\",180,y,\"Smoky,\r\ngrilled\"").single()

        assertEquals("Paneer \"Special\", grilled", row.name)
        assertEquals("Smoky,\r\ngrilled", row.description)
        assertEquals(18_000, row.pricePaise)
    }

    @Test
    fun rejects_original_price_not_above_sale_price() {
        assertThrows(IllegalArgumentException::class.java) {
            parseDishCsv("category,name,price,original_price,veg,description,variant_name,variant_price,variant_original_price\r\nMains,Dosa,100,100,y,,,,")
        }
    }

    @Test
    fun rejects_unconfigured_language_columns() {
        assertThrows(IllegalArgumentException::class.java) {
            parseDishCsv(menuCsvTemplate(listOf("hi")), emptyList())
        }
    }

    @Test
    fun rejects_unsupported_add_on_columns_instead_of_dropping_them() {
        assertThrows(IllegalArgumentException::class.java) {
            parseDishCsv("category,name,price,addon_group\r\nMains,Dosa,100,Extras")
        }
    }

    @Test
    fun export_keeps_category_and_dish_translations_and_variant_compare_price() {
        val state = MenuUiState(
            categories = listOf(Category("cat", "rest", "Mains", kind = "beverage", nameI18n = mapOf("hi" to "मुख्य"))),
            dishes = listOf(Dish(
                id = "dish", restaurantId = "rest", categoryId = "cat", name = "Tea",
                description = "Fresh, hot", basePrice = 10_000, originalPrice = 12_000,
                nameI18n = mapOf("hi" to "चाय"), descriptionI18n = mapOf("hi" to "गरम"),
            )),
            variants = listOf(DishVariant("variant", "dish", "Full", 8_000, 9_000)),
            languages = listOf("hi"),
        )

        val row = parseDishCsv(exportMenuCsv(state), listOf("hi")).single()

        assertEquals("beverage", row.categoryKind)
        assertEquals("मुख्य", row.categoryI18n["hi"])
        assertEquals("चाय", row.nameI18n["hi"])
        assertEquals("Fresh, hot", row.description)
        assertEquals(12_000, row.originalPricePaise)
        assertEquals("Full", row.variantName)
        assertEquals(9_000, row.variantOriginalPricePaise)
    }
}
