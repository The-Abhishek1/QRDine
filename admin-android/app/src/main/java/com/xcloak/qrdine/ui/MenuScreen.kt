package com.xcloak.qrdine.ui

import android.net.Uri
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.UploadFile
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
    val c = if (isVeg) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
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
                onSave(name, kind, tr.filterValues { it.isNotBlank() }.takeIf { it.isNotEmpty() }, normalizeTime(from), normalizeTime(to)); onDismiss()
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
    onOffers: () -> Unit = {},
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
    var showCsvHelp by remember { mutableStateOf(false) }
    var showCsvExportHelp by remember { mutableStateOf(false) }
    val t = target

    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("The selected file could not be opened.")
            vm.importCsv(text)
        } catch (e: Exception) {
            vm.reportCsvReadError(e)
        }
    }
    val templatePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) runCatching {
            val output = context.contentResolver.openOutputStream(uri) ?: error("No output stream")
            output.use { it.write(menuCsvTemplate(s.languages).toByteArray(Charsets.UTF_8)) }
        }.onSuccess { vm.reportCsvSaved("CSV template saved") }.onFailure { vm.reportCsvWriteError(it as? Exception ?: Exception(it)) }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) runCatching {
            val output = context.contentResolver.openOutputStream(uri) ?: error("No output stream")
            output.use { it.write(exportMenuCsv(s).toByteArray(Charsets.UTF_8)) }
        }.onSuccess { vm.reportCsvSaved("Menu CSV exported") }.onFailure { vm.reportCsvWriteError(it as? Exception ?: Exception(it)) }
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
                        DropdownMenuItem(text = { Text("Import dishes (CSV)") }, leadingIcon = { Icon(Icons.Default.UploadFile, null) }, onClick = { menuOpen = false; showCsvHelp = true })
                        DropdownMenuItem(text = { Text("Export menu (CSV)") }, onClick = { menuOpen = false; showCsvExportHelp = true })
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
                    Surface(
                        modifier = Modifier.size(84.dp),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
                        }
                    }
                    ScreenIntro("BUILD YOUR CATALOG", "Start with a category", "For example Starters, Mains, or Beverages. You can also import dishes from a CSV.")
                    Button({ categoryDialog = true }) { Text("Add category") }
                    OutlinedButton(onClick = { showCsvHelp = true }) {
                        Icon(Icons.Default.UploadFile, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Import menu from CSV")
                    }
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    s.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
                    item(key = "menu-tools") {
                        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("MENU TOOLS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text("Customize what guests can add or save.", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onAddonGroups, Modifier.weight(1f)) {
                                    Text("Extras\n${s.groups.size} groups · ${s.addons.count { it.isAvailable }} choices")
                                }
                                OutlinedButton(onOffers, Modifier.weight(1f)) { Text("Offers · ${s.offers.count { it.active }}") }
                            }
                            s.stockError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (reorder) item { Text("Use the arrows to change the order guests see. Tap Done when finished.", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodySmall) }
                    s.categories.forEachIndexed { ci, cat ->
                        item(key = "c-${cat.id}") {
                            var catMenu by remember { mutableStateOf(false) }
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(cat.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
                                .clickable(enabled = !reorder) { target = EditorTarget.Edit(d) }) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (d.imageUrl != null) AsyncImage(d.imageUrl, d.name, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
                                else Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        VegDot(d.isVeg)
                                        Text(d.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
                                    }
                                    Text(d.basePrice.rupees(), style = MaterialTheme.typography.bodyMedium)
                                    val extras = s.links.filter { it.dishId == d.id }.mapNotNull { link ->
                                        val group = s.groups.firstOrNull { it.id == link.groupId } ?: return@mapNotNull null
                                        val items = s.addons.filter { it.groupId == group.id }.joinToString { it.name }
                                        "${group.name}${if (group.minSelect == 0) " (optional)" else " (required)"}${items.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}"
                                    }
                                    if (extras.isNotEmpty()) Text("Extras · ${extras.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
                                    if (d.stockQty != null) Text("${d.stockQty} left", style = MaterialTheme.typography.labelMedium, color = if (d.stockQty <= 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (!d.isAvailable) Text("Out of stock", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                                }
                                if (reorder) {
                                    IconButton({ vm.moveDish(d, -1) }, enabled = idx > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                                    IconButton({ vm.moveDish(d, 1) }, enabled = idx < dishes.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                                } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Menu", style = MaterialTheme.typography.labelSmall)
                                    Switch(checked = d.isAvailable, onCheckedChange = { vm.toggleAvailable(d) })
                                    Text("Online", style = MaterialTheme.typography.labelSmall)
                                    Switch(checked = d.onlineAvailable, onCheckedChange = { vm.toggleOnlineAvailable(d) })
                                }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCsvHelp) {
        CsvImportGuide(
            onDismiss = { showCsvHelp = false },
            languages = s.languages,
            onDownloadTemplate = {
                showCsvHelp = false
                templatePicker.launch("qrdine-menu-template.csv")
            },
            onChooseFile = {
                showCsvHelp = false
                csvPicker.launch("text/*")
            },
        )
    }
    if (showCsvExportHelp) {
        AlertDialog(
            onDismissRequest = { showCsvExportHelp = false },
            title = { Text("Export menu CSV?") },
            text = { Text("The export contains categories, localized category/dish names and descriptions, vegetarian status, base and variant prices, and original prices. Add-on groups and their choices, photos, stock counts, and availability settings are not represented in this CSV and will not be exported.") },
            confirmButton = {
                Button(onClick = {
                    showCsvExportHelp = false
                    exportPicker.launch("qrdine-menu.csv")
                }) { Text("Choose save location") }
            },
            dismissButton = { TextButton(onClick = { showCsvExportHelp = false }) { Text("Cancel") } },
        )
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

@Composable
private fun CsvImportGuide(onDismiss: () -> Unit, languages: List<String>, onDownloadTemplate: () -> Unit, onChooseFile: () -> Unit) {
    val sample = menuCsvTemplate(languages).lineSequence().take(3).joinToString("\n")
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.UploadFile, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Prepare your menu CSV") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Download the template for the exact columns matching this restaurant's enabled languages.", style = MaterialTheme.typography.bodyMedium)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Text(
                        sample,
                        Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    )
                }
                Text("Each row has category, dish name, base selling price (₹), optional base original/list price, veg (y/n), description and language columns. For sizes, repeat the same dish fields on one row per size and fill variant_name, variant_price and optional variant_original_price. Size names such as Half and Full are labels; the database has no separate numeric quantity field. Every original price must be greater than its matching selling price.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("CSV supports quoted commas, escaped quotes (\"\"), and quoted line breaks. Import adds dishes and creates missing categories. Add-on groups/options are stored as separate linked records and are not represented or imported by this CSV; set them up in Extras after import.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = onChooseFile) {
                    Icon(Icons.Default.UploadFile, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose CSV file")
                }
                TextButton(onClick = onDownloadTemplate) { Text("Download CSV template") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
