package com.xcloak.qrdine.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.xcloak.qrdine.data.Category
import com.xcloak.qrdine.data.Dish

sealed interface EditorTarget {
    data object New : EditorTarget
    data class Edit(val dish: Dish) : EditorTarget
}

fun Int.rupees(): String = if (this % 100 == 0) "₹${this / 100}" else "₹%.2f".format(this / 100.0)

@Composable
fun VegDot(isVeg: Boolean) {
    val c = if (isVeg) Color(0xFF2E7D32) else Color(0xFFC62828)
    Box(Modifier.size(16.dp).border(BorderStroke(1.5.dp, c), RoundedCornerShape(3.dp)).padding(3.dp)) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(c))
    }
}

@Composable
private fun CategoryDialog(initial: Category?, languages: List<String>, onDismiss: () -> Unit, onSave: (String, String, Map<String, String>?, String?, String?) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var kind by remember { mutableStateOf(initial?.kind ?: "food") }
    var from by remember { mutableStateOf(initial?.availFrom?.take(5) ?: "") }
    var to by remember { mutableStateOf(initial?.availTo?.take(5) ?: "") }
    val tr = remember { mutableStateMapOf<String, String>().apply { initial?.nameI18n?.let { putAll(it) } } }
    val timeOk = (from.isBlank() && to.isBlank()) || (TIME_OF_DAY.matches(from) && TIME_OF_DAY.matches(to))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New category" else "Edit category") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true)
                if (initial == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == "food", { kind = "food" }, label = { Text("Food") })
                    FilterChip(kind == "beverage", { kind = "beverage" }, label = { Text("Beverage") })
                }
                Text("Served only at certain hours? (optional, 24-hour time)", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(from, { from = it.take(5) }, label = { Text("From (07:00)") }, singleLine = true, isError = !timeOk, modifier = Modifier.weight(1f))
                    OutlinedTextField(to, { to = it.take(5) }, label = { Text("To (11:30)") }, singleLine = true, isError = !timeOk, modifier = Modifier.weight(1f))
                }
                languages.forEach { l ->
                    OutlinedTextField(tr[l].orEmpty(), { tr[l] = it.take(60) }, label = { Text("Name in ${LANG_NAMES[l] ?: l}") }, singleLine = true)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && timeOk, onClick = {
                onSave(name, kind, tr.filterValues { it.isNotBlank() }.takeIf { it.isNotEmpty() }, from.ifBlank { null }, to.ifBlank { null }); onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuHost(
    vm: MenuViewModel,
    bottomBar: @Composable () -> Unit = {},
    onAddonGroups: () -> Unit = {},
    authVm: AuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var target by remember { mutableStateOf<EditorTarget?>(null) }
    var categoryDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var reorder by remember { mutableStateOf(false) }
    var editCategory by remember { mutableStateOf<Category?>(null) }
    var deleteFor by remember { mutableStateOf<Category?>(null) }
    val t = target

    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            vm.importCsv(text)
        }
    }
    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); vm.messageShown() } }

    if (t != null) {
        BackHandler { target = null }
        val existing = (t as? EditorTarget.Edit)?.dish
        DishEditorScreen(
            existing = existing,
            categories = s.categories,
            variants = s.variants.filter { it.dishId == existing?.id },
            groups = s.groups,
            linked = s.links.filter { it.dishId == existing?.id }.map { it.groupId }.toSet(),
            languages = s.languages,
            saving = s.saving,
            error = s.error,
            onSave = { form, bytes, variants, groupIds -> vm.saveDish(existing, form, bytes, variants, groupIds) { target = null } },
            onDelete = { d -> vm.deleteDish(d) { target = null } },
            onBack = { target = null; vm.clearError() },
        )
        return
    }

    Scaffold(
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(s.restaurantName.ifBlank { "Menu" }, fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (reorder) TextButton({ reorder = false }) { Text("Done") }
                    IconButton({ menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Add category") }, onClick = { menuOpen = false; categoryDialog = true })
                        DropdownMenuItem(text = { Text("Add-on groups (extras)") }, onClick = { menuOpen = false; onAddonGroups() })
                        DropdownMenuItem(text = { Text("Reorder items") }, onClick = { menuOpen = false; reorder = true })
                        DropdownMenuItem(text = { Text("Import dishes (CSV)") }, onClick = { menuOpen = false; csvPicker.launch("*/*") })
                        DropdownMenuItem(text = { Text("Refresh") }, onClick = { menuOpen = false; vm.refresh() })
                        DropdownMenuItem(text = { Text("Sign out") }, onClick = { menuOpen = false; authVm.signOut() })
                    }
                },
            )
        },
        floatingActionButton = {
            if (s.categories.isNotEmpty() && !reorder) {
                ExtendedFloatingActionButton(onClick = { target = EditorTarget.New }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add dish") })
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                s.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                s.categories.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Start by adding a category", style = MaterialTheme.typography.titleMedium)
                    Text("For example Starters, Mains, Beverages. Or import a CSV of your dishes.", style = MaterialTheme.typography.bodyMedium)
                    Button({ categoryDialog = true }) { Text("Add category") }
                    OutlinedButton({ csvPicker.launch("*/*") }) { Text("Import dishes (CSV)") }
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    s.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
                    if (reorder) item { Text("Use the arrows to change the order guests see. Tap Done when finished.", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodySmall) }
                    s.categories.forEachIndexed { ci, cat ->
                        item(key = "c-${cat.id}") {
                            var catMenu by remember { mutableStateOf(false) }
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(cat.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    if (cat.availFrom != null && cat.availTo != null) Text("Served ${cat.availFrom.take(5)} to ${cat.availTo.take(5)}", style = MaterialTheme.typography.bodySmall)
                                }
                                if (reorder) {
                                    IconButton({ vm.moveCategory(cat, -1) }, enabled = ci > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move category up") }
                                    IconButton({ vm.moveCategory(cat, 1) }, enabled = ci < s.categories.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Move category down") }
                                } else Box {
                                    IconButton({ catMenu = true }) { Icon(Icons.Default.MoreVert, "Category options") }
                                    DropdownMenu(catMenu, { catMenu = false }) {
                                        DropdownMenuItem(text = { Text("Edit name, hours, translations") }, onClick = { catMenu = false; editCategory = cat })
                                        DropdownMenuItem(text = { Text("Delete category") }, onClick = { catMenu = false; deleteFor = cat })
                                    }
                                }
                            }
                        }
                        val dishes = s.dishes.filter { it.categoryId == cat.id }
                        if (dishes.isEmpty()) item(key = "e-${cat.id}") { Text("No dishes yet", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
                        items(dishes, key = { it.id }) { d ->
                            val idx = dishes.indexOf(d)
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !reorder) { target = EditorTarget.Edit(d) }.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (d.imageUrl != null) AsyncImage(d.imageUrl, d.name, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
                                else Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        VegDot(d.isVeg)
                                        Text(d.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
                                    }
                                    Text(d.basePrice.rupees(), style = MaterialTheme.typography.bodyMedium)
                                    if (d.stockQty != null) Text("${d.stockQty} left", style = MaterialTheme.typography.labelMedium, color = if (d.stockQty <= 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (!d.isAvailable) Text("Out of stock", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                                }
                                if (reorder) {
                                    IconButton({ vm.moveDish(d, -1) }, enabled = idx > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                                    IconButton({ vm.moveDish(d, 1) }, enabled = idx < dishes.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                                } else Switch(checked = d.isAvailable, onCheckedChange = { vm.toggleAvailable(d) })
                            }
                        }
                    }
                }
            }
        }
    }

    if (categoryDialog) CategoryDialog(null, s.languages, { categoryDialog = false }) { name, kind, i18n, from, to -> vm.addCategory(name, kind, i18n, from, to) }
    editCategory?.let { c -> CategoryDialog(c, s.languages, { editCategory = null }) { name, _, i18n, from, to -> vm.updateCategory(c, name, i18n, from, to) } }
    deleteFor?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${c.name}?") },
            text = { Text("Only an empty category can be deleted. Delete or move its dishes first.") },
            confirmButton = { TextButton({ vm.deleteCategory(c.id); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton({ deleteFor = null }) { Text("Cancel") } },
        )
    }
}
