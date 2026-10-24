package com.xcloak.qrdine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.*
import kotlinx.coroutines.launch

private data class Line(val dish: Dish, val variant: DishVariant?, val addons: List<Addon>, val qty: Int) {
    val key get() = dish.id + "|" + (variant?.id ?: "") + "|" + addons.map { it.id }.sorted().joinToString(",")
    val unit get() = (variant?.price ?: dish.basePrice) + addons.sumOf { it.price }
    val label get() = dish.name + (variant?.let { " (${it.name})" } ?: "") + (if (addons.isEmpty()) "" else " + " + addons.joinToString(", ") { it.name })
}

private class MenuData(
    val categories: List<Category>, val dishes: List<Dish>, val variants: List<DishVariant>,
    val groups: List<AddonGroup>, val addons: List<Addon>, val links: List<DishAddonLink>,
)

/** Staff enter an order for a table (verbal or phone orders, or a forgotten dish). It goes straight to the kitchen as accepted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddItemsScreen(restaurantId: String, sessionId: String, tableLabel: String, onClose: () -> Unit) {
    val container = QrDineApp.container
    val repo = remember { MenuRepository(container.supabase, restaurantId) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var data by remember { mutableStateOf<MenuData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val lines = remember { mutableStateListOf<Line>() }
    var parcel by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var optionsFor by remember { mutableStateOf<Dish?>(null) }
    var sending by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    LaunchedEffect(Unit) {
        try {
            data = MenuData(repo.categories(), repo.dishes(), repo.variants(), repo.groups(), repo.addons(), repo.links())
        } catch (e: Exception) { error = (e.message ?: "Could not load the menu").take(160) }
    }

    fun add(l: Line) {
        val i = lines.indexOfFirst { it.key == l.key }
        if (i >= 0) lines[i] = lines[i].copy(qty = (lines[i].qty + l.qty).coerceAtMost(50)) else lines.add(l)
    }
    fun friendly(e: Exception): String {
        val m = e.message ?: return "Something went wrong"
        return when {
            "dish_unavailable" in m -> "Not available: " + m.substringAfter("dish_unavailable:", "").substringBefore("\"").take(60)
            "variant_required" in m -> "Choose a size/variant for that dish."
            "addon_choice" in m -> "Check the extras for that dish."
            "session_closed" in m -> "That table has been closed."
            else -> m.take(160)
        }
    }

    val d = data
    val total = lines.sumOf { it.unit.toLong() * it.qty }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Add items · Table $tableLabel", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            if (lines.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                        lines.toList().forEach { l ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${l.qty} × ${l.label}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text((l.unit.toLong() * l.qty).rupees())
                                TextButton({ val i = lines.indexOfFirst { it.key == l.key }; if (i >= 0) { if (l.qty <= 1) { lines.removeAt(i) } else { lines[i] = l.copy(qty = l.qty - 1) } } }) { Text("−") }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(!parcel, { parcel = false }, label = { Text("Eat here") })
                        FilterChip(parcel, { parcel = true }, label = { Text("Parcel") })
                    }
                    OutlinedTextField(note, { note = it.take(300) }, label = { Text("Note for the kitchen (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(
                        {
                            sending = true
                            scope.launch {
                                try {
                                    container.orders.addOrder(sessionId, lines.map { StaffItem(it.dish.id, it.qty, it.variant?.id, it.addons.map { a -> a.id }) }, parcel, note)
                                    container.ordersStore.refresh()
                                    onClose()
                                } catch (e: Exception) { snackbar.showSnackbar(friendly(e)) }
                                finally { sending = false }
                            }
                        },
                        enabled = !sending, modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Send to kitchen · ${total.rupees()}") }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                error != null -> Text(error!!, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                d == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp)) {
                    d.categories.forEach { cat ->
                        val dishes = d.dishes.filter { it.categoryId == cat.id && it.isAvailable }
                        if (dishes.isNotEmpty()) {
                            item(key = "c-${cat.id}") { Text(cat.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)) }
                            items(dishes, key = { it.id }) { dish ->
                                val hasOptions = d.variants.any { it.dishId == dish.id } || d.links.any { it.dishId == dish.id }
                                val inCart = lines.filter { it.dish.id == dish.id }.sumOf { it.qty }
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(dish.name, fontWeight = FontWeight.Medium)
                                        Text(dish.basePrice.toLong().rupees() + if (hasOptions) "+" else "", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (inCart > 0) Text("$inCart", Modifier.padding(end = 8.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    FilledTonalButton({ if (hasOptions) optionsFor = dish else add(Line(dish, null, emptyList(), 1)) }) { Text("Add") }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    optionsFor?.let { dish ->
        val dd = data ?: return@let
        val variants = dd.variants.filter { it.dishId == dish.id }
        val groups = dd.groups.filter { g -> dd.links.any { it.dishId == dish.id && it.groupId == g.id } }
        var variant by remember(dish) { mutableStateOf(variants.firstOrNull()) }
        var picked by remember(dish) { mutableStateOf(setOf<String>()) }
        val valid = groups.all { g -> val n = dd.addons.count { it.groupId == g.id && it.id in picked }; n >= g.minSelect && n <= g.maxSelect }
        AlertDialog(
            onDismissRequest = { optionsFor = null },
            title = { Text(dish.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (variants.isNotEmpty()) {
                        Text("Choose one", fontWeight = FontWeight.SemiBold)
                        variants.forEach { v -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(variant?.id == v.id, { variant = v }); Text("${v.name}  ${v.price.toLong().rupees()}") } }
                    }
                    groups.forEach { g ->
                        Text(g.name + " (" + (if (g.minSelect > 0) "required, " else "") + (if (g.maxSelect == 1) "pick one" else "up to ${g.maxSelect}") + ")", fontWeight = FontWeight.SemiBold)
                        dd.addons.filter { it.groupId == g.id && it.isAvailable }.forEach { a ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(a.id in picked, {
                                    picked = if (a.id in picked) picked - a.id
                                    else if (g.maxSelect == 1) picked.filter { id -> dd.addons.none { it.id == id && it.groupId == g.id } }.toSet() + a.id
                                    else if (dd.addons.count { it.groupId == g.id && it.id in picked } < g.maxSelect) picked + a.id else picked
                                })
                                Text("${a.name}  +${a.price.toLong().rupees()}")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = { add(Line(dish, variant, dd.addons.filter { it.id in picked }, 1)); optionsFor = null }) { Text("Add") }
            },
            dismissButton = { TextButton({ optionsFor = null }) { Text("Cancel") } },
        )
    }
}
