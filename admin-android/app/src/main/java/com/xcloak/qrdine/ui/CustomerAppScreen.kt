package com.xcloak.qrdine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.xcloak.qrdine.Diagnostics
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.OnlineRestaurant
import com.xcloak.qrdine.data.OnlineCartLine
import com.xcloak.qrdine.data.OnlineMenu
import com.xcloak.qrdine.data.OnlineMenuDish
import com.xcloak.qrdine.data.OnlineAddon
import com.xcloak.qrdine.data.OnlineOffer
import com.xcloak.qrdine.data.OnlineVariant
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerAppScreen() {
    val scope = rememberCoroutineScope()
    var restaurants by remember { mutableStateOf<List<OnlineRestaurant>>(emptyList()) }
    var selectedRestaurant by remember { mutableStateOf<OnlineRestaurant?>(null) }
    var menu by remember { mutableStateOf<OnlineMenu?>(null) }
    var menuLoading by remember { mutableStateOf(false) }
    var menuRequestId by remember { mutableIntStateOf(0) }
    var configuring by remember { mutableStateOf<OnlineMenuDish?>(null) }
    var cart by remember { mutableStateOf<List<OnlineCartLine>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadRestaurants() {
        scope.launch {
            loading = true
            error = null
            try {
                restaurants = QrDineApp.container.customer.restaurants()
            } catch (e: Exception) {
                Diagnostics.record(QrDineApp.appContext, e, "customer-directory")
                error = "Restaurants could not be loaded. Check your connection and try again."
            } finally {
                loading = false
            }
        }
    }

    fun openRestaurant(restaurant: OnlineRestaurant) {
        val requestId = ++menuRequestId
        selectedRestaurant = restaurant
        menu = null
        cart = emptyList()
        menuLoading = true
        error = null
        scope.launch {
            try {
                val response = QrDineApp.container.customer.menu(restaurant.id)
                if (requestId == menuRequestId) {
                    selectedRestaurant = response.restaurant
                    menu = response.menu
                }
            } catch (e: Exception) {
                Diagnostics.record(QrDineApp.appContext, e, "customer-menu")
                if (requestId == menuRequestId) {
                    error = "This menu could not be loaded. Check your connection and try again."
                }
            } finally {
                if (requestId == menuRequestId) menuLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadRestaurants() }
    val filtered = restaurants.filter {
        query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) ||
            it.address.orEmpty().contains(query.trim(), ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Order food", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (selectedRestaurant != null) {
                        IconButton(onClick = {
                            menuRequestId++
                            selectedRestaurant = null
                            menu = null
                            cart = emptyList()
                            error = null
                        }) { Text("←", style = MaterialTheme.typography.titleLarge) }
                    }
                },
                actions = {
                    OutlinedButton(onClick = {
                        scope.launch {
                            try {
                                QrDineApp.container.auth.signOut()
                            } catch (e: Exception) {
                                Diagnostics.record(QrDineApp.appContext, e, "customer-signout")
                                error = "Could not sign out. Please try again."
                            }
                        }
                    }) { Text("Sign out") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selectedRestaurant != null) {
                val restaurant = selectedRestaurant!!
                Text(restaurant.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    buildList {
                        if (restaurant.deliveryEnabled) add("Delivery ${restaurant.deliveryFee.rupees()}")
                        if (restaurant.pickupEnabled) add("Pickup")
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                when {
                    menuLoading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    error != null -> {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                        Button(onClick = { openRestaurant(restaurant) }) { Text("Try again") }
                    }
                    menu != null -> {
                        menu!!.announcement?.let {
                            Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(12.dp)) }
                        }
                        val onlineMenu = menu!!
                        val cartCount = cart.sumOf { it.qty }
                        val subtotal = cart.sumOf { it.qty * it.unitPrice }
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (onlineMenu.offers.isNotEmpty()) {
                                item(key = "online-offers") {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Offers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        onlineMenu.offers.forEach { offer ->
                                            val buyDish = onlineMenu.dishes.firstOrNull { it.id == offer.buyDishId }
                                            val rewardDish = onlineMenu.dishes.firstOrNull { it.id == offer.rewardDishId }
                                            if (buyDish != null && rewardDish != null) {
                                                Card(Modifier.fillMaxWidth()) {
                                                    Column(Modifier.padding(12.dp)) {
                                                        Text(offer.name, fontWeight = FontWeight.SemiBold)
                                                        Text("Buy ${offer.buyQty} ${buyDish.name}, get ${offer.getQty} ${rewardDish.name}")
                                                        Text("Add the reward item to qualify.", style = MaterialTheme.typography.bodySmall)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            onlineMenu.categories.sortedBy { it.sort }.forEach { category ->
                                item(key = "cat-${category.id}") {
                                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                        Text(category.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        if (!category.openNow) {
                                            Text("Not available right now", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                items(onlineMenu.dishes.filter { it.categoryId == category.id }, key = { it.id }) { dish ->
                                    val dishCartQty = cart.filter { it.dishId == dish.id }.sumOf { it.qty }
                                    val available = dish.isAvailable && category.openNow && (dish.stockLeft == null || dish.stockLeft > 0)
                                    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Text(dish.name, fontWeight = FontWeight.SemiBold)
                                                val startingVariant = dish.variants.minByOrNull { it.price }
                                                val currentPrice = startingVariant?.price ?: dish.basePrice
                                                val originalPrice = startingVariant?.originalPrice ?: dish.originalPrice
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        currentPrice.rupees() + if (dish.variants.isNotEmpty()) "+" else "",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                    )
                                                    if (originalPrice != null && originalPrice > currentPrice) {
                                                        Text(
                                                            originalPrice.rupees(),
                                                            style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough),
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                                dish.description?.takeIf(String::isNotBlank)?.let {
                                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                                }
                                                if (dish.stockLeft != null && dish.stockLeft in 1..5) {
                                                    Text("${dish.stockLeft} left", style = MaterialTheme.typography.labelSmall)
                                                }
                                                if (dishCartQty > 0) Text("$dishCartQty in your selection", style = MaterialTheme.typography.labelSmall)
                                            }
                                            if (available) {
                                                OutlinedButton(
                                                    onClick = { configuring = dish },
                                                    enabled = dishCartQty < minOf(dish.stockLeft ?: 50, 50),
                                                ) { Text("Add") }
                                            } else {
                                                Text(
                                                    if (!dish.isAvailable || dish.stockLeft == 0) "Sold out" else "Not available",
                                                    color = MaterialTheme.colorScheme.error,
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (cartCount > 0) {
                            HorizontalDivider()
                            Text("$cartCount items · ${subtotal.rupees()}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            cart.forEach { line ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${line.qty} × ${line.name} · ${line.unitPrice.rupees()}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = {
                                        cart = cart.flatMap { item ->
                                            if (item.key != line.key) listOf(item)
                                            else if (item.qty <= 1) emptyList() else listOf(item.copy(qty = item.qty - 1))
                                        }
                                    }) { Text("−") }
                                    TextButton(onClick = {
                                        val dish = onlineMenu.dishes.firstOrNull { it.id == line.dishId }
                                        val totalForDish = cart.filter { it.dishId == line.dishId }.sumOf { it.qty }
                                        if (totalForDish < (dish?.stockLeft ?: 50) && line.qty < 50) {
                                            cart = cart.map { item -> if (item.key == line.key) item.copy(qty = item.qty + 1) else item }
                                        }
                                    }) { Text("+") }
                                }
                            }
                            Text(
                                "Checkout and order placement will be available in the next stage.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                Text("Restaurants accepting online orders", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search restaurants or area") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                } else if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = ::loadRestaurants) { Text("Try again") }
                } else if (filtered.isEmpty()) {
                    Text(
                        if (restaurants.isEmpty()) "No restaurants are accepting online orders yet."
                        else "No restaurants match your search.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (restaurants.isEmpty()) {
                        Text(
                            "Restaurant owners must enable online ordering before appearing here.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(filtered, key = { it.id }) { restaurant ->
                            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(restaurant.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    restaurant.address?.takeIf(String::isNotBlank)?.let {
                                        Text(it, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (restaurant.deliveryEnabled) Text("Delivery")
                                        if (restaurant.pickupEnabled) Text("Pickup")
                                        if (restaurant.upiEnabled) Text("UPI")
                                        if (restaurant.codEnabled) Text("Cash on delivery")
                                    }
                                    if (restaurant.deliveryEnabled && restaurant.deliveryFee > 0) {
                                        Text("Delivery fee ${restaurant.deliveryFee.rupees()}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (restaurant.codEnabled && restaurant.codFee > 0) {
                                        Text("COD fee ${restaurant.codFee.rupees()}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    OutlinedButton(onClick = { openRestaurant(restaurant) }) { Text("View menu") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    configuring?.let { dish ->
        OnlineDishPicker(
            dish = dish,
            onDismiss = { configuring = null },
            onAdd = { line ->
                val existing = cart.firstOrNull { it.key == line.key }
                cart = if (existing == null) cart + line else cart.map {
                    if (it.key == line.key) it.copy(qty = it.qty + 1) else it
                }
                configuring = null
            },
        )
    }
}

@Composable
private fun OnlineDishPicker(
    dish: OnlineMenuDish,
    onDismiss: () -> Unit,
    onAdd: (OnlineCartLine) -> Unit,
) {
    var variantId by remember(dish.id) { mutableStateOf(dish.variants.firstOrNull()?.id) }
    val selectedAddons = remember(dish.id) { mutableStateMapOf<String, Set<String>>() }
    val invalidGroup = dish.addonGroups.firstOrNull { group ->
        val selected = selectedAddons[group.id].orEmpty().size
        selected < group.minSelect || selected > group.maxSelect
    }
    val impossibleGroup = dish.addonGroups.firstOrNull { group ->
        group.minSelect > group.addons.count { it.isAvailable }
    }
    val variant = dish.variants.firstOrNull { it.id == variantId }
    val selected = dish.addonGroups.flatMap { group ->
        group.addons.filter { it.id in selectedAddons[group.id].orEmpty() }
    }
    val unitPrice = (variant?.price ?: dish.basePrice) + selected.sumOf { it.price }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dish.name) },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (dish.variants.isNotEmpty()) {
                    Text("Choose size · required", style = MaterialTheme.typography.labelLarge)
                    dish.variants.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = variantId == option.id, onClick = { variantId = option.id })
                            Text("${option.name} · ${option.price.rupees()}")
                        }
                    }
                }
                dish.addonGroups.forEach { group ->
                    Text(
                        "${group.name} · ${if (group.minSelect == 0) "optional" else "choose at least ${group.minSelect}"}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    group.addons.filter { it.isAvailable }.forEach { addon ->
                        val chosen = addon.id in selectedAddons[group.id].orEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (group.maxSelect == 1) {
                                RadioButton(
                                    selected = chosen,
                                    onClick = {
                                        selectedAddons[group.id] =
                                            if (chosen) emptySet() else setOf(addon.id)
                                    },
                                )
                            } else {
                                Checkbox(
                                    checked = chosen,
                                    enabled = chosen || selectedAddons[group.id].orEmpty().size < group.maxSelect,
                                    onCheckedChange = { checked ->
                                        selectedAddons[group.id] = if (checked) {
                                            selectedAddons[group.id].orEmpty() + addon.id
                                        } else {
                                            selectedAddons[group.id].orEmpty() - addon.id
                                        }
                                    },
                                )
                            }
                            Text("${addon.name}${if (addon.price > 0) " · +${addon.price.rupees()}" else ""}")
                        }
                    }
                }
                impossibleGroup?.let {
                    Text(
                        "Not enough available options for ${it.name}.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } ?: invalidGroup?.let {
                    Text(
                        if (selectedAddons[it.id].orEmpty().size < it.minSelect) "Select at least ${it.minSelect} from ${it.name}."
                        else "Choose no more than ${it.maxSelect} from ${it.name}.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = (dish.variants.isEmpty() || variant != null) && invalidGroup == null && impossibleGroup == null,
                onClick = {
                    val variantChoice: OnlineVariant? = variant
                    val names = selected.map(OnlineAddon::name)
                    val ids = selected.map(OnlineAddon::id).sorted()
                    val lineName = dish.name +
                        (variantChoice?.let { " (${it.name})" } ?: "") +
                        (names.takeIf { it.isNotEmpty() }?.joinToString(prefix = " + ") ?: "")
                    val key = listOf(dish.id, variantChoice?.id.orEmpty(), ids.joinToString(",")).joinToString("|")
                    onAdd(
                        OnlineCartLine(
                            key, dish.id, lineName, 1, unitPrice, variantChoice?.id, variantChoice?.name, ids, names,
                        ),
                    )
                },
            ) { Text("Add · ${unitPrice.rupees()}") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
