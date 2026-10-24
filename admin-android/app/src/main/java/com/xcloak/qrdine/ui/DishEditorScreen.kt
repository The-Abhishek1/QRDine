package com.xcloak.qrdine.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.xcloak.qrdine.data.AddonGroup
import com.xcloak.qrdine.data.Category
import com.xcloak.qrdine.data.Dish
import com.xcloak.qrdine.data.DishForm
import com.xcloak.qrdine.data.DishVariant
import com.xcloak.qrdine.data.ImageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

private fun parsePaise(text: String): Int? = runCatching {
    val v = BigDecimal(text.trim())
    if (v.signum() < 0 || v.scale() > 2) null else v.movePointRight(2).toInt()
}.getOrNull()

private fun rs(paise: Int) = (paise / 100.0).toString().removeSuffix(".0")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DishEditorScreen(
    existing: Dish?,
    categories: List<Category>,
    variants: List<DishVariant>,
    groups: List<AddonGroup>,
    linked: Set<String>,
    languages: List<String>,
    saving: Boolean,
    error: String?,
    onSave: (DishForm, ByteArray?, List<Pair<String, Int>>, Set<String>) -> Unit,
    onDelete: (Dish) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var description by rememberSaveable { mutableStateOf(existing?.description ?: "") }
    var price by rememberSaveable { mutableStateOf(existing?.let { rs(it.basePrice) } ?: "") }
    var isVeg by rememberSaveable { mutableStateOf(existing?.isVeg ?: true) }
    var categoryId by rememberSaveable { mutableStateOf(existing?.categoryId ?: categories.firstOrNull()?.id ?: "") }
    var stock by rememberSaveable { mutableStateOf(existing?.stockQty?.toString() ?: "") }
    val nameTr = remember { mutableStateMapOf<String, String>().apply { existing?.nameI18n?.let { putAll(it) } } }
    val descTr = remember { mutableStateMapOf<String, String>().apply { existing?.descriptionI18n?.let { putAll(it) } } }
    val vNames = remember { mutableStateListOf<String>().apply { addAll(variants.map { it.name }) } }
    val vPrices = remember { mutableStateListOf<String>().apply { addAll(variants.map { rs(it.price) }) } }
    var picked by remember { mutableStateOf(linked) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var imageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var imageError by remember { mutableStateOf<String?>(null) }
    var catOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            pickedUri = uri
            scope.launch {
                try { imageBytes = withContext(Dispatchers.IO) { ImageUtil.compress(context, uri) }; imageError = null }
                catch (e: Exception) { imageBytes = null; pickedUri = null; imageError = "Could not use that image" }
            }
        }
    }

    val pricePaise = parsePaise(price)
    val variantList = vNames.indices.map { vNames[it].trim() to parsePaise(vPrices[it]) }
    val variantsOk = variantList.all { it.first.isNotBlank() && it.second != null && it.second!! > 0 }
    val stockOk = stock.isBlank() || stock.toIntOrNull() != null
    val canSave = name.isNotBlank() && pricePaise != null && categoryId.isNotBlank() && variantsOk && stockOk && !saving

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add dish" else "Edit dish") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val preview: Any? = pickedUri ?: existing?.imageUrl
            Box(
                Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                contentAlignment = Alignment.Center,
            ) {
                if (preview != null) AsyncImage(preview, name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) else Text("Tap to add a photo")
            }
            imageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            OutlinedTextField(name, { name = it.take(120) }, label = { Text("Dish name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it.take(500) }, label = { Text("Description (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                price, { price = it }, label = { Text("Price (₹)") }, singleLine = true,
                isError = price.isNotBlank() && pricePaise == null,
                supportingText = { Text(if (price.isNotBlank() && pricePaise == null) "Use a number like 120 or 120.50" else if (vNames.isNotEmpty()) "Guests pay the price of the size they pick; this is the fallback." else "") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )

            ExposedDropdownMenuBox(catOpen, { catOpen = it }) {
                OutlinedTextField(
                    categories.firstOrNull { it.id == categoryId }?.name ?: "", {}, readOnly = true, label = { Text("Category") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(catOpen) }, modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(catOpen, { catOpen = false }) {
                    categories.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { categoryId = c.id; catOpen = false }) }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                VegDot(isVeg); Spacer(Modifier.width(8.dp))
                Text(if (isVeg) "Vegetarian" else "Non-vegetarian", Modifier.weight(1f))
                Switch(isVeg, { isVeg = it })
            }

            OutlinedTextField(
                stock, { stock = it.filter(Char::isDigit).take(5) }, label = { Text("Stock count (optional)") }, singleLine = true, isError = !stockOk,
                supportingText = { Text("Leave empty for unlimited. Each order reduces it and the dish sells out by itself at zero.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()
            Text("Sizes (optional)", style = MaterialTheme.typography.titleMedium)
            Text("For example Half and Full. The guest must pick one.", style = MaterialTheme.typography.bodySmall)
            vNames.indices.forEach { i ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(vNames[i], { vNames[i] = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(vPrices[i], { vPrices[i] = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, label = { Text("₹") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(100.dp))
                    TextButton({ vNames.removeAt(i); vPrices.removeAt(i) }) { Text("✕") }
                }
            }
            OutlinedButton({ vNames.add(""); vPrices.add("") }) { Text("Add a size") }

            if (groups.isNotEmpty()) {
                HorizontalDivider()
                Text("Extras (add-on groups)", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    groups.forEach { g -> FilterChip(g.id in picked, { picked = if (g.id in picked) picked - g.id else picked + g.id }, label = { Text(g.name) }) }
                }
            } else Text("Create extras like Cheese or Spice level from the menu's three-dot menu, Add-on groups.", style = MaterialTheme.typography.bodySmall)

            if (languages.isNotEmpty()) {
                HorizontalDivider()
                Text("Translations (optional)", style = MaterialTheme.typography.titleMedium)
                languages.forEach { l ->
                    OutlinedTextField(nameTr[l].orEmpty(), { nameTr[l] = it.take(120) }, label = { Text("Name in ${LANG_NAMES[l] ?: l}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(descTr[l].orEmpty(), { descTr[l] = it.take(500) }, label = { Text("Description in ${LANG_NAMES[l] ?: l}") }, minLines = 1, modifier = Modifier.fillMaxWidth())
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    onSave(
                        DishForm(
                            name, description, pricePaise!!, isVeg, categoryId, stock.toIntOrNull(),
                            nameTr.filterValues { it.isNotBlank() }.takeIf { it.isNotEmpty() }, descTr.filterValues { it.isNotBlank() }.takeIf { it.isNotEmpty() },
                        ),
                        imageBytes, variantList.map { it.first to it.second!! }, picked,
                    )
                },
                enabled = canSave, modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { if (saving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Save") }
            if (existing != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true }, enabled = !saving,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete dish") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            text = { Text("It disappears from the menu. Past bills keep their record of it.") },
            confirmButton = { TextButton({ confirmDelete = false; onDelete(existing) }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
