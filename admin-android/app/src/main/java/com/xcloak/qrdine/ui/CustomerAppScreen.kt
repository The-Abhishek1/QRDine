package com.xcloak.qrdine.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.xcloak.qrdine.Diagnostics
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.OnlineRestaurant
import com.xcloak.qrdine.data.OnlineCartLine
import com.xcloak.qrdine.data.OnlineCustomerProfile
import com.xcloak.qrdine.data.OnlineMenu
import com.xcloak.qrdine.data.OnlineMenuDish
import com.xcloak.qrdine.data.OnlineAddon
import com.xcloak.qrdine.data.OnlineOrder
import com.xcloak.qrdine.data.OnlineOrderInputItem
import com.xcloak.qrdine.data.OnlineOffer
import com.xcloak.qrdine.data.OnlineVariant
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerAppScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var restaurants by remember { mutableStateOf<List<OnlineRestaurant>>(emptyList()) }
    var selectedRestaurant by remember { mutableStateOf<OnlineRestaurant?>(null) }
    var menu by remember { mutableStateOf<OnlineMenu?>(null) }
    var menuLoading by remember { mutableStateOf(false) }
    var menuRequestId by remember { mutableIntStateOf(0) }
    var configuring by remember { mutableStateOf<OnlineMenuDish?>(null) }
    var cart by remember { mutableStateOf<List<OnlineCartLine>>(emptyList()) }
    var customerProfile by remember { mutableStateOf<OnlineCustomerProfile?>(null) }
    var customerOrders by remember { mutableStateOf<List<OnlineOrder>>(emptyList()) }
    var checkoutOpen by remember { mutableStateOf(false) }
    var activeOrder by remember { mutableStateOf<OnlineOrder?>(null) }
    var paymentReference by remember { mutableStateOf("") }
    var clientOrderKeys by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var orderBusy by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var directoryError by remember { mutableStateOf<String?>(null) }
    var profileError by remember { mutableStateOf<String?>(null) }
    var profileEditorOpen by remember { mutableStateOf(false) }
    var serviceFilter by rememberSaveable { mutableStateOf("all") }
    var menuQuery by rememberSaveable { mutableStateOf("") }
    var menuCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val customerRoleReady = remember { QrDineApp.container.auth.customerRoleConfigured() }

    fun loadRestaurants() {
        scope.launch {
            loading = true
            directoryError = null
            try {
                restaurants = QrDineApp.container.customer.restaurants()
            } catch (e: Exception) {
                Diagnostics.record(QrDineApp.appContext, e, "customer-directory")
                directoryError = restaurantDirectoryError(e)
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
        menuQuery = ""
        menuCategoryId = null
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

    fun refreshRestaurantMenu(restaurant: OnlineRestaurant) {
        val requestId = ++menuRequestId
        menuLoading = true
        scope.launch {
            try {
                val response = QrDineApp.container.customer.menu(restaurant.id)
                if (requestId == menuRequestId) {
                    selectedRestaurant = response.restaurant
                    menu = response.menu
                    error = null
                }
            } catch (e: Exception) {
                Diagnostics.record(QrDineApp.appContext, e, "customer-menu-refresh")
                if (requestId == menuRequestId) error = "Could not refresh this restaurant's menu. Please try again."
            } finally {
                if (requestId == menuRequestId) menuLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadRestaurants()
        try {
            if (customerRoleReady) {
                customerProfile = QrDineApp.container.customer.profile()
                customerOrders = QrDineApp.container.customer.orders()
            } else {
                profileError = "Customer ordering permissions are not enabled for this account yet. The Supabase admin must enable the custom access-token hook, then sign out and sign in again."
            }
        } catch (e: Exception) {
            Diagnostics.record(QrDineApp.appContext, e, "customer-profile")
            profileError = "Your customer profile and order history could not be loaded. Check that the online-ordering migrations are deployed."
        }
    }

    fun submitOrder(fulfilment: String, payment: String) {
        val restaurant = selectedRestaurant ?: return
        val onlineMenu = menu ?: return
        scope.launch {
            orderBusy = true
            error = null
            try {
                val clientKey = clientOrderKeys[restaurant.id] ?: java.util.UUID.randomUUID().toString()
                clientOrderKeys = clientOrderKeys + (restaurant.id to clientKey)
                val result = QrDineApp.container.customer.placeOrder(
                    restaurant.id,
                    clientKey,
                    fulfilment,
                    payment,
                    cart.map { line ->
                        OnlineOrderInputItem(line.dishId, line.qty, line.variantId, line.addonIds, "")
                    },
                )
                clientOrderKeys = clientOrderKeys - restaurant.id
                val deliveryFee = if (fulfilment == "delivery") restaurant.deliveryFee else 0
                val codFee = if (payment == "cod") restaurant.codFee else 0
                val createdOrder = OnlineOrder(
                    id = result.orderId,
                    restaurantId = restaurant.id,
                    customerId = "",
                    status = "pending",
                    fulfilment = fulfilment,
                    paymentMethod = payment,
                    paymentStatus = if (payment == "upi") "awaiting_payment" else "cod_due",
                    customerName = customerProfile?.fullName.orEmpty(),
                    customerPhone = customerProfile?.phone.orEmpty(),
                    items = emptyList(),
                    subtotal = result.total - deliveryFee - codFee,
                    deliveryFee = deliveryFee,
                    codFee = codFee,
                    total = result.total,
                    createdAt = "",
                )
                activeOrder = createdOrder
                cart = emptyList()
                checkoutOpen = false
                customerOrders = listOf(createdOrder) + customerOrders.filterNot { it.id == createdOrder.id }
                if (payment == "upi") error = "Order placed. Pay by UPI and submit the payment reference for restaurant verification."
                try {
                    customerOrders = QrDineApp.container.customer.orders()
                    activeOrder = customerOrders.firstOrNull { it.id == result.orderId } ?: createdOrder
                } catch (e: Exception) {
                    Diagnostics.record(QrDineApp.appContext, e, "customer-orders-refresh")
                    error = "Order placed, but its latest status could not be refreshed. Tap Refresh in recent orders to check again."
                }
            } catch (e: Exception) {
                Diagnostics.record(QrDineApp.appContext, e, "customer-online-order")
                error = when {
                    "profile_required" in e.message.orEmpty() -> "Complete your customer profile before placing an order."
                    "cod_unavailable" in e.message.orEmpty() -> "Cash on delivery is not currently available."
                    "restaurant_unavailable" in e.message.orEmpty() -> "This restaurant is no longer accepting online orders."
                    else -> "Could not place the order. Check your connection and try again."
                }
            } finally {
                orderBusy = false
            }
        }
    }
    val filtered = restaurants.filter { restaurant ->
        val matchesSearch = query.isBlank() || restaurant.name.contains(query.trim(), ignoreCase = true) ||
            restaurant.address.orEmpty().contains(query.trim(), ignoreCase = true)
        val matchesService = when (serviceFilter) {
            "delivery" -> restaurant.deliveryEnabled
            "pickup" -> restaurant.pickupEnabled
            else -> true
        }
        matchesSearch && matchesService
    }

    Scaffold(
        bottomBar = {
            if (selectedRestaurant == null) {
                NavigationBar {
                    listOf(
                        Triple("Home", Icons.Default.Home, 0),
                        Triple("Search", Icons.Default.Search, 1),
                        Triple("Orders", Icons.Default.ReceiptLong, 2),
                        Triple("Profile", Icons.Default.Person, 3),
                    ).forEach { (label, icon, index) ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { androidx.compose.material3.Icon(icon, contentDescription = label) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        selectedRestaurant?.name ?: listOf("Discover", "Find restaurants", "Your orders", "Your profile")[selectedTab],
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    if (selectedRestaurant != null) {
                        IconButton(onClick = {
                            checkoutOpen = false
                            activeOrder = null
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (selectedRestaurant != null) {
                val restaurant = selectedRestaurant!!
                if (!customerRoleReady) {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    ) {
                        Text(
                            "Browsing is available, but checkout needs the Supabase customer access-token hook enabled. After it is enabled, sign out and back in.",
                            Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                CustomerRestaurantHero(
                    restaurant = restaurant,
                    onRefresh = { refreshRestaurantMenu(restaurant) },
                    refreshing = menuLoading,
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                )
                {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            buildList {
                                if (restaurant.deliveryEnabled) add("Delivery ${restaurant.deliveryFee.rupees()}")
                                if (restaurant.pickupEnabled) add("Pickup")
                            }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (restaurant.address != null) Text(restaurant.address, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${cart.sumOf { it.qty }} in cart", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
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
                        val menuCategories = onlineMenu.categories.sortedBy { it.sort }.filter { category ->
                            (menuCategoryId == null || category.id == menuCategoryId) &&
                                onlineMenu.dishes.any { dish ->
                                    dish.categoryId == category.id &&
                                        (menuQuery.isBlank() || dish.name.contains(menuQuery.trim(), ignoreCase = true) ||
                                            dish.description.orEmpty().contains(menuQuery.trim(), ignoreCase = true))
                                }
                        }
                        val cartCount = cart.sumOf { it.qty }
                        val subtotal = cart.sumOf { it.qty * it.unitPrice }
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            item(key = "menu-search") {
                                OutlinedTextField(
                                    value = menuQuery,
                                    onValueChange = { menuQuery = it },
                                    label = { Text("Search dishes") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (onlineMenu.categories.size > 1) {
                                item(key = "menu-category-filters") {
                                    Row(
                                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        FilterChip(
                                            selected = menuCategoryId == null,
                                            onClick = { menuCategoryId = null },
                                            label = { Text("All") },
                                        )
                                        onlineMenu.categories.sortedBy { it.sort }.forEach { category ->
                                            FilterChip(
                                                selected = menuCategoryId == category.id,
                                                onClick = { menuCategoryId = if (menuCategoryId == category.id) null else category.id },
                                                label = { Text(category.name) },
                                            )
                                        }
                                    }
                                }
                            }
                            item(key = "menu-help") {
                                Text(
                                    "Tap Add to cart to choose a dish. Sizes and optional add-ons are selected before it is added.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                                                        Text("The reward is added automatically when your order qualifies.", style = MaterialTheme.typography.bodySmall)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            menuCategories.forEach { category ->
                                item(key = "cat-${category.id}") {
                                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                        Text(category.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        if (!category.openNow) {
                                            Text("Not available right now", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                items(
                                    onlineMenu.dishes.filter { dish ->
                                        dish.categoryId == category.id &&
                                            (menuQuery.isBlank() || dish.name.contains(menuQuery.trim(), ignoreCase = true) ||
                                                dish.description.orEmpty().contains(menuQuery.trim(), ignoreCase = true))
                                    },
                                    key = { it.id },
                                ) { dish ->
                                    val dishCartQty = cart.filter { it.dishId == dish.id }.sumOf { it.qty }
                                    val available = dish.isAvailable && dish.onlineAvailable && category.openNow && (dish.stockLeft == null || dish.stockLeft > 0)
                                    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (!dish.imageUrl.isNullOrBlank()) {
                                                CustomerPhoto(
                                                    url = dish.imageUrl,
                                                    label = dish.name,
                                                    modifier = Modifier.size(72.dp),
                                                )
                                            }
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
                                                ) { Text("Add to cart") }
                                            } else {
                                                Text(
                                                    if (!dish.isAvailable || dish.stockLeft == 0) "Sold out" else if (!dish.onlineAvailable) "Not available online" else "Not available",
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
                                "Online order total includes the fees shown at checkout.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { checkoutOpen = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !orderBusy && customerRoleReady,
                            ) { Text("Continue to checkout") }
                        }
                        activeOrder?.let { order ->
                            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Order ${order.id.take(8)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                            Text(order.status.replace('_', ' ').uppercase(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                        }
                                        IconButton(onClick = {
                                            scope.launch {
                                                orderBusy = true
                                                try {
                                                    customerOrders = QrDineApp.container.customer.orders()
                                                    activeOrder = customerOrders.firstOrNull { it.id == order.id } ?: order
                                                    error = null
                                                } catch (e: Exception) {
                                                    Diagnostics.record(QrDineApp.appContext, e, "customer-order-detail-refresh")
                                                    error = "Could not refresh this order. Please try again."
                                                } finally {
                                                    orderBusy = false
                                                }
                                            }
                                        }, enabled = !orderBusy) {
                                            androidx.compose.material3.Icon(Icons.Default.Refresh, contentDescription = "Refresh order details")
                                        }
                                    }
                                    Text("${order.fulfilment.replace('_', ' ').replaceFirstChar(Char::uppercase)} · ${order.paymentMethod.uppercase()} · ${order.paymentStatus.replace('_', ' ')}")
                                    if (order.etaMinutes != null && order.status == "accepted") {
                                        Text("Estimated preparation time: ${order.etaMinutes} minutes", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    if (order.items.isNotEmpty()) {
                                        HorizontalDivider()
                                        Text("Items", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                        order.items.forEach { item ->
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text("${item.qty} × ${item.name}" + (item.variantName?.let { " · $it" } ?: ""), Modifier.weight(1f))
                                                    Text(((item.qty - item.offerFreeQty).coerceAtLeast(0) * item.unitPrice).rupees())
                                                }
                                                item.offerFreeQty.takeIf { it > 0 }?.let {
                                                    Text("$it free with offer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                                }
                                                item.addons.takeIf { it.isNotEmpty() }?.let { addons ->
                                                    Text("Add-ons: ${addons.joinToString { addon -> addon.name }}", style = MaterialTheme.typography.bodySmall)
                                                }
                                                item.instructions?.takeIf(String::isNotBlank)?.let { Text("Note: $it", style = MaterialTheme.typography.bodySmall) }
                                            }
                                        }
                                    }
                                    HorizontalDivider()
                                    Text("Items ${order.subtotal.rupees()}", style = MaterialTheme.typography.bodySmall)
                                    if (order.offerDiscount > 0) Text("Offer discount −${order.offerDiscount.rupees()}", style = MaterialTheme.typography.bodySmall)
                                    if (order.deliveryFee > 0) Text("Delivery ${order.deliveryFee.rupees()}", style = MaterialTheme.typography.bodySmall)
                                    if (order.codFee > 0) Text("COD fee ${order.codFee.rupees()}", style = MaterialTheme.typography.bodySmall)
                                    Text("Total ${order.total.rupees()}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    order.rejectReason?.let { Text("Reason: $it", color = MaterialTheme.colorScheme.error) }
                                    if (order.customerRating != null) {
                                        Text("Your rating: ${"★".repeat(order.customerRating)}${"☆".repeat(5 - order.customerRating)}", fontWeight = FontWeight.SemiBold)
                                        order.customerFeedback?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                    }
                                    if (order.paymentMethod == "upi" && order.paymentStatus in setOf("awaiting_payment", "verification_required")) {
                                        val upiId = restaurant.upiId
                                        if (!upiId.isNullOrBlank()) {
                                            if (order.paymentStatus == "awaiting_payment") Button(onClick = {
                                                val uri = Uri.parse(
                                                    "upi://pay?pa=${Uri.encode(upiId)}&pn=${Uri.encode(restaurant.name)}&am=${"%.2f".format(java.util.Locale.US, order.total / 100.0)}&cu=INR&tn=${Uri.encode("Online order ${order.id.take(8)}")}",
                                                )
                                                try {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                                } catch (e: Exception) {
                                                    Diagnostics.record(QrDineApp.appContext, e, "customer-upi")
                                                    error = "No UPI payment app is available on this device."
                                                }
                                            }) { Text("Pay with UPI") }
                                            OutlinedTextField(
                                                value = paymentReference.ifBlank { order.paymentReference.orEmpty() },
                                                onValueChange = { paymentReference = it.take(100) },
                                                label = { Text("UPI transaction reference") },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        orderBusy = true
                                                        try {
                                                            QrDineApp.container.customer.submitPaymentReference(order.id, paymentReference)
                                                            val updated = order.copy(
                                                                paymentStatus = "verification_required",
                                                                paymentReference = paymentReference.trim(),
                                                            )
                                                            customerOrders = customerOrders.map { if (it.id == order.id) updated else it }
                                                            activeOrder = updated
                                                            paymentReference = ""
                                                            try {
                                                                customerOrders = QrDineApp.container.customer.orders()
                                                                activeOrder = customerOrders.firstOrNull { it.id == order.id } ?: updated
                                                            } catch (e: Exception) {
                                                                Diagnostics.record(QrDineApp.appContext, e, "customer-orders-refresh")
                                                                error = "Reference sent, but the latest order status could not be refreshed."
                                                            }
                                                        } catch (e: Exception) {
                                                            Diagnostics.record(QrDineApp.appContext, e, "customer-payment-reference")
                                                            error = "Could not submit the payment reference. Please try again."
                                                        } finally {
                                                            orderBusy = false
                                                        }
                                                    }
                                                },
                                                enabled = paymentReference.trim().length >= 6 && !orderBusy,
                                            ) { Text(if (order.paymentStatus == "awaiting_payment") "Submit payment reference" else "Update payment reference") }
                                            Text(
                                                "The restaurant verifies your UPI transfer before accepting the order.",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (customerOrders.isNotEmpty()) {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Your recent orders", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    TextButton(onClick = {
                                        scope.launch {
                                            try {
                                                customerOrders = QrDineApp.container.customer.orders()
                                                error = null
                                            } catch (e: Exception) {
                                                Diagnostics.record(QrDineApp.appContext, e, "customer-orders-refresh")
                                                error = "Could not refresh your order status. Please try again."
                                            }
                                        }
                                    }) { Text("Refresh") }
                                }
                                customerOrders.filter { it.restaurantId == restaurant.id }.take(5).forEach { order ->
                                    Card(
                                        onClick = { activeOrder = order },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text("${order.id.take(8)} · ${order.status.uppercase()}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                            Text(order.total.rupees(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else when (selectedTab) {
                0, 1 -> {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (selectedTab == 0) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(26.dp),
                                    colors = androidx.compose.material3.CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(7.dp),
                                    ) {
                                        Text("GOOD FOOD, CLOSE BY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        Text("What are you craving?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                        Text(
                                            "Discover nearby restaurants, explore their menus, and order for delivery or pickup.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            "${restaurants.size} restaurants available",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                            if (!customerRoleReady) {
                                item {
                                    Card(
                                        Modifier.fillMaxWidth(),
                                        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                                    ) {
                                        Text(
                                            "Customer browsing works, but profile and checkout need the Supabase customer access-token hook enabled.",
                                            Modifier.padding(14.dp),
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        } else {
                            item {
                                Text("Search by restaurant name or area.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text("Search restaurants or area") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                leadingIcon = { androidx.compose.material3.Icon(Icons.Default.Search, contentDescription = null) },
                            )
                        }
                        item {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = serviceFilter == "all",
                                    onClick = { serviceFilter = "all" },
                                    label = { Text("All") },
                                )
                                FilterChip(
                                    selected = serviceFilter == "delivery",
                                    onClick = { serviceFilter = "delivery" },
                                    label = { Text("Delivery") },
                                )
                                FilterChip(
                                    selected = serviceFilter == "pickup",
                                    onClick = { serviceFilter = "pickup" },
                                    label = { Text("Pickup") },
                                )
                            }
                        }
                        item {
                            Text(
                                if (query.isBlank()) "Restaurants accepting online orders" else "Search results",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        if (loading) {
                            item { CircularProgressIndicator(Modifier.padding(vertical = 18.dp)) }
                        } else if (directoryError != null) {
                            item {
                                Card(
                                    Modifier.fillMaxWidth(),
                                    colors = androidx.compose.material3.CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                    ),
                                ) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Restaurants couldn’t load", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                        Text(directoryError!!, color = MaterialTheme.colorScheme.onErrorContainer)
                                        OutlinedButton(onClick = ::loadRestaurants) { Text("Try again") }
                                    }
                                }
                            }
                        } else if (filtered.isEmpty()) {
                            item {
                                GlassCard(Modifier.fillMaxWidth()) {
                                    Text(
                                        if (restaurants.isEmpty()) "No restaurants are accepting online orders yet."
                                        else "No restaurants match your search or filter.",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        if (restaurants.isEmpty()) "Approved restaurants appear here when they enable online ordering."
                                        else "Try another name, area, or service filter.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (restaurants.isEmpty()) Button(onClick = ::loadRestaurants) { Text("Refresh restaurants") }
                                }
                            }
                        } else {
                            items(filtered, key = { it.id }) { restaurant ->
                                CustomerRestaurantCard(restaurant) { openRestaurant(restaurant) }
                            }
                        }
                    }
                }
                2 -> {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Track recent orders and payment status.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    if (!customerRoleReady) {
                                        profileError = "Enable the customer access-token hook in Supabase before loading orders."
                                    } else {
                                        try {
                                            customerOrders = QrDineApp.container.customer.orders()
                                            profileError = null
                                        } catch (e: Exception) {
                                            Diagnostics.record(QrDineApp.appContext, e, "customer-orders-refresh")
                                            profileError = "Order history could not be refreshed. Check the online-order migration and try again."
                                        }
                                    }
                                }
                            }) { androidx.compose.material3.Icon(Icons.Default.Refresh, contentDescription = "Refresh orders") }
                        }
                        profileError?.let {
                            Card(Modifier.fillMaxWidth(), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                                Text(it, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }
                        if (customerOrders.isEmpty()) {
                            GlassCard(Modifier.fillMaxWidth()) {
                                Text("No orders yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Your order updates will appear here after you place an order.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(onClick = { selectedTab = 0 }) { Text("Explore restaurants") }
                            }
                        } else {
                            LazyColumn(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(customerOrders, key = { it.id }) { order ->
                                    val restaurant = restaurants.firstOrNull { it.id == order.restaurantId }
                                    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(restaurant?.name ?: "Restaurant order", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                                    Text("Order ${order.id.take(8)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                Text(order.status.replace('_', ' ').uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                            }
                                            Text("${order.fulfilment.replace('_', ' ')} · ${order.paymentMethod.uppercase()} · ${order.paymentStatus.replace('_', ' ')}")
                                            order.items.take(3).forEach { Text("${it.qty} × ${it.name}", style = MaterialTheme.typography.bodySmall) }
                                            Text("Total ${order.total.rupees()}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                            if (order.rejectReason != null) Text(order.rejectReason, color = MaterialTheme.colorScheme.error)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                if (restaurant != null) OutlinedButton(onClick = {
                                                    activeOrder = order
                                                    openRestaurant(restaurant)
                                                    activeOrder = order
                                                }) { Text("View order") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        ) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("YOUR QR DINE ACCOUNT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(customerProfile?.fullName ?: "Welcome to QR Dine", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text(QrDineApp.container.auth.email().orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        profileError?.let {
                            Card(Modifier.fillMaxWidth(), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                                Text(it, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text("Delivery profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (customerProfile == null) {
                                Text("Add your name, mobile number, and address before your first order.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(customerProfile!!.fullName, fontWeight = FontWeight.SemiBold)
                                Text(customerProfile!!.phone)
                                Text("${customerProfile!!.address}, ${customerProfile!!.postalCode}")
                            }
                            Button(
                                onClick = { profileEditorOpen = true },
                                enabled = customerRoleReady,
                            ) { Text(if (customerProfile == null) "Complete profile" else "Edit profile") }
                        }
                        ThemePreferenceCard()
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
    if (checkoutOpen && selectedRestaurant != null) {
        OnlineCheckoutDialog(
            profile = customerProfile,
            restaurant = selectedRestaurant!!,
            subtotal = cart.sumOf { it.qty * it.unitPrice },
            busy = orderBusy,
            error = error,
            onDismiss = { checkoutOpen = false },
            onSaveProfile = { profile ->
                scope.launch {
                    orderBusy = true
                    try {
                        QrDineApp.container.customer.saveProfile(profile)
                        customerProfile = profile
                        error = null
                    } catch (e: Exception) {
                        Diagnostics.record(QrDineApp.appContext, e, "customer-profile-save")
                        error = "Could not save your profile. Check the phone, address, and PIN code."
                    } finally {
                        orderBusy = false
                    }
                }
            },
            onPlaceOrder = ::submitOrder,
        )
    }
    if (profileEditorOpen) {
        CustomerProfileDialog(
            profile = customerProfile,
            busy = orderBusy,
            onDismiss = { profileEditorOpen = false },
            onSave = { updated ->
                scope.launch {
                    orderBusy = true
                    profileError = null
                    try {
                        QrDineApp.container.customer.saveProfile(updated)
                        customerProfile = updated
                        profileEditorOpen = false
                    } catch (e: Exception) {
                        Diagnostics.record(QrDineApp.appContext, e, "customer-profile-save")
                        profileError = "Could not save your profile. Check your mobile number and PIN code."
                    } finally {
                        orderBusy = false
                    }
                }
            },
        )
    }
}

private fun restaurantDirectoryError(error: Exception): String {
    val message = error.message.orEmpty()
    val code = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"")
        .find(message)?.groupValues?.getOrNull(1)
    return when {
        code == "server_error" ->
            "The restaurant service returned a backend error. Deploy the online-restaurants Edge Function and the latest online-ordering database migrations."
        code == "rate_limited" ->
            "Too many restaurant requests were made. Wait a minute, then try again."
        error::class.simpleName?.contains("NotFound", ignoreCase = true) == true ->
            "The online-restaurants Edge Function was not found. Deploy it to this Supabase project."
        message.contains("resolve host", ignoreCase = true) || message.contains("connect", ignoreCase = true) ->
            "Could not connect to Supabase. Check the project URL and your internet connection."
        else ->
            "Restaurant request failed (${error::class.simpleName ?: "network error"}). Check the Supabase project URL and that online-restaurants is deployed."
    }
}

@Composable
private fun CustomerRestaurantHero(
    restaurant: OnlineRestaurant,
    onRefresh: () -> Unit,
    refreshing: Boolean,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column {
            CustomerPhoto(
                restaurant.logoUrl,
                restaurant.name,
                Modifier.fillMaxWidth().height(160.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(restaurant.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    restaurant.address?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onRefresh, enabled = !refreshing) {
                    if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else androidx.compose.material3.Icon(Icons.Default.Refresh, contentDescription = "Refresh restaurant menu")
                }
            }
        }
    }
}

@Composable
private fun CustomerPhoto(
    url: String?,
    label: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var failed by remember(url) { mutableStateOf(false) }
    if (!url.isNullOrBlank() && !failed) {
        AsyncImage(
            model = url,
            contentDescription = "$label image",
            contentScale = contentScale,
            alignment = Alignment.Center,
            modifier = modifier.clip(RoundedCornerShape(14.dp)),
            onError = { failed = true },
        )
    } else {
        Box(
            modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.material3.Icon(
                    Icons.Default.Restaurant,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(32.dp),
                )
                Text(label, color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun CustomerRestaurantCard(restaurant: OnlineRestaurant, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            CustomerPhoto(restaurant.logoUrl, restaurant.name, Modifier.fillMaxWidth().height(170.dp))
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(restaurant.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        restaurant.address?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("MENU  ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (restaurant.deliveryEnabled) CustomerServiceTag("Delivery · ${restaurant.deliveryFee.rupees()}")
                    if (restaurant.pickupEnabled) CustomerServiceTag("Pickup")
                    if (restaurant.upiEnabled) CustomerServiceTag("UPI")
                    if (restaurant.codEnabled) CustomerServiceTag("COD")
                }
                if (restaurant.codEnabled && restaurant.codFee > 0) {
                    Text("Cash on delivery fee ${restaurant.codFee.rupees()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CustomerServiceTag(label: String) {
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun CustomerProfileDialog(
    profile: OnlineCustomerProfile?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (OnlineCustomerProfile) -> Unit,
) {
    var name by remember(profile) { mutableStateOf(profile?.fullName.orEmpty()) }
    var phone by remember(profile) { mutableStateOf(profile?.phone.orEmpty()) }
    var address by remember(profile) { mutableStateOf(profile?.address.orEmpty()) }
    var postalCode by remember(profile) { mutableStateOf(profile?.postalCode.orEmpty()) }
    val valid = name.trim().length in 2..80 &&
        Regex("^(\\+91)?[6-9][0-9]{9}$").matches(phone.trim()) &&
        address.trim().length in 5..250 &&
        Regex("^[0-9]{6}$").matches(postalCode.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delivery profile") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("These details are used when you place an online order.")
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Full name") }, singleLine = true)
                OutlinedTextField(phone, { phone = it.take(13) }, label = { Text("Indian mobile number") }, singleLine = true)
                OutlinedTextField(address, { address = it.take(250) }, label = { Text("Address") }, minLines = 2)
                OutlinedTextField(postalCode, { postalCode = it.filter(Char::isDigit).take(6) }, label = { Text("PIN code") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(OnlineCustomerProfile(name.trim(), phone.trim(), address.trim(), postalCode.trim())) },
                enabled = valid && !busy,
            ) {
                if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                else Text("Save profile")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun OnlineCheckoutDialog(
    profile: OnlineCustomerProfile?,
    restaurant: OnlineRestaurant,
    subtotal: Int,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSaveProfile: (OnlineCustomerProfile) -> Unit,
    onPlaceOrder: (String, String) -> Unit,
) {
    var editingProfile by remember(profile) { mutableStateOf(profile == null) }
    var fullName by remember(profile) { mutableStateOf(profile?.fullName.orEmpty()) }
    var phone by remember(profile) { mutableStateOf(profile?.phone.orEmpty()) }
    var address by remember(profile) { mutableStateOf(profile?.address.orEmpty()) }
    var postalCode by remember(profile) { mutableStateOf(profile?.postalCode.orEmpty()) }
    var fulfilment by remember(restaurant) {
        mutableStateOf(if (restaurant.deliveryEnabled) "delivery" else "pickup")
    }
    var payment by remember(restaurant) {
        mutableStateOf(if (restaurant.upiEnabled) "upi" else "cod")
    }
    val profileValid = fullName.trim().length in 2..80 &&
        Regex("^(\\+91)?[6-9][0-9]{9}$").matches(phone.trim()) &&
        address.trim().length in 5..250 && Regex("^[0-9]{6}$").matches(postalCode.trim())
    val total = subtotal +
        (if (fulfilment == "delivery") restaurant.deliveryFee else 0) +
        (if (payment == "cod") restaurant.codFee else 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Checkout · ${restaurant.name}") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (editingProfile) {
                    Text("Complete your customer profile before ordering.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(fullName, { fullName = it.take(80) }, label = { Text("Full name") }, singleLine = true)
                    OutlinedTextField(phone, { phone = it.take(13) }, label = { Text("Mobile number") }, singleLine = true)
                    OutlinedTextField(address, { address = it.take(250) }, label = { Text("Delivery address") }, minLines = 2)
                    OutlinedTextField(postalCode, { postalCode = it.filter(Char::isDigit).take(6) }, label = { Text("PIN code") }, singleLine = true)
                    Button(
                        onClick = {
                            onSaveProfile(OnlineCustomerProfile(fullName.trim(), phone.trim(), address.trim(), postalCode.trim()))
                        },
                        enabled = profileValid && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save customer profile") }
                } else {
                    Text("${profile?.fullName} · ${profile?.phone}", fontWeight = FontWeight.SemiBold)
                    Text("${profile?.address}, ${profile?.postalCode}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { editingProfile = true }) { Text("Edit profile") }
                    Text("Fulfilment", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (restaurant.deliveryEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = fulfilment == "delivery", onClick = { fulfilment = "delivery" })
                            Text("Delivery · ${restaurant.deliveryFee.rupees()}")
                        }
                    }
                    if (restaurant.pickupEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = fulfilment == "pickup", onClick = { fulfilment = "pickup" })
                            Text("Pickup")
                        }
                    }
                    Text("Payment", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (restaurant.upiEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = payment == "upi", onClick = { payment = "upi" })
                            Text("UPI — restaurant verifies payment")
                        }
                    }
                    if (restaurant.codEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = payment == "cod", onClick = { payment = "cod" })
                            Text("Cash on delivery · ${restaurant.codFee.rupees()}")
                        }
                    }
                    HorizontalDivider()
                    Text("Items ${subtotal.rupees()}")
                    if (fulfilment == "delivery") Text("Delivery fee ${restaurant.deliveryFee.rupees()}")
                    if (payment == "cod") Text("COD fee ${restaurant.codFee.rupees()}")
                    Text("Total ${total.rupees()}", fontWeight = FontWeight.Bold)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (payment == "upi") {
                        Text(
                            "After placing your order, pay using UPI and submit the transaction reference. The restaurant verifies the transfer before accepting.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = { onPlaceOrder(fulfilment, payment) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy) "Placing order…" else "Place order") }
                }
                if (error != null && editingProfile) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
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
