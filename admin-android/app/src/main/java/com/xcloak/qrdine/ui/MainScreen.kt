package com.xcloak.qrdine.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.HotelStatus
import com.xcloak.qrdine.notify.AlertControl
import com.xcloak.qrdine.notify.Fcm
import com.xcloak.qrdine.notify.Prefs
import kotlinx.coroutines.delay

private data class NavDef(val label: String, val icon: ImageVector)

private val NAV = mapOf(
    "orders" to NavDef("Orders", Icons.Default.Notifications),
    "tables" to NavDef("Tables", Icons.Default.Home),
    "menu" to NavDef("Menu", Icons.AutoMirrored.Filled.List),
    "insights" to NavDef("Insights", Icons.Default.Star),
    "settings" to NavDef("Settings", Icons.Default.Settings),
)

/** Owner: everything. Waiter: orders, tables, bills. Kitchen: the kitchen screen only. Wide screens get a side rail. */
@Composable
fun MainScreen(restaurantId: String, role: String) {
    val context = LocalContext.current
    val store = QrDineApp.container.ordersStore
    val snap by store.snapshot.collectAsStateWithLifecycle()
    val authVm: AuthViewModel = viewModel()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var hotelStatus by remember { mutableStateOf<HotelStatus?>(null) }
    var kitchenMode by remember { mutableStateOf(Prefs.kitchenMode(context)) }
    var billSession by rememberSaveable { mutableStateOf<String?>(null) }
    var billTable by rememberSaveable { mutableStateOf("") }
    var addItemsSession by rememberSaveable { mutableStateOf<String?>(null) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showCash by rememberSaveable { mutableStateOf(false) }
    var showAddons by rememberSaveable { mutableStateOf(false) }
    var showOffers by rememberSaveable { mutableStateOf(false) }

    // keyed by restaurant so another login never reuses the previous restaurant's state
    val menuVm: MenuViewModel? = if (role == "owner") viewModel<MenuViewModel>(key = "menu-$restaurantId", factory = MenuViewModel.Factory(restaurantId)) else null
    val tablesVm: TablesViewModel = viewModel(key = "tables-$restaurantId", factory = TablesViewModel.Factory(restaurantId))
    val menuState by (menuVm?.state?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(MenuUiState()) })
    val tablesState by tablesVm.state.collectAsStateWithLifecycle()
    var showSetupGuide by rememberSaveable(restaurantId) { mutableStateOf(false) }
    val setupGuidePreferences = remember(context) {
        context.getSharedPreferences("setup_guide", android.content.Context.MODE_PRIVATE)
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(restaurantId) {
        store.start(restaurantId)
        Fcm.registerIfPossible(context)
        if (role == "owner") tablesVm.refresh()
    }
    LaunchedEffect(restaurantId, role, menuState.loading, menuState.dishes.isEmpty(), tablesState.loading, tablesState.tables.isEmpty()) {
        val incomplete = menuState.dishes.isEmpty() || tablesState.tables.isEmpty()
        if (
            role == "owner" &&
            !menuState.loading &&
            !tablesState.loading &&
            incomplete &&
            !setupGuidePreferences.getBoolean("dismissed_$restaurantId", false)
        ) showSetupGuide = true
    }
    // the alert service follows the open/closed switch (a counter tablet or waiter phone rings too). The kitchen rings for accepted orders instead.
    LaunchedEffect(snap.loaded, snap.isOpen, role) {
        if (snap.loaded && role != "kitchen") AlertControl.sync(context, snap.isOpen)
    }
    // status every minute: renewal banners, broadcasts, and the move to the blocked screen the moment the hotel is held or the login turned off
    LaunchedEffect(Unit) {
        while (true) {
            val st = QrDineApp.container.owner.hotelStatus()
            if (st != null) hotelStatus = st
            if (st != null && st.state in setOf("pending", "rejected", "hold", "disabled")) runCatching { QrDineApp.container.auth.refresh() }
            delay(60_000)
        }
    }

    if (role == "kitchen" || (role == "owner" && kitchenMode)) {
        KitchenScreen(
            exitLabel = if (role == "kitchen") "Sign out" else "Exit kitchen mode",
            onExit = { if (role == "kitchen") authVm.signOut() else { Prefs.setKitchenMode(context, false); kitchenMode = false } },
        )
        return
    }

    val addItems = addItemsSession
    if (addItems != null) {
        AddItemsScreen(restaurantId, addItems, billTable, onClose = { addItemsSession = null })
        return
    }
    val session = billSession
    if (session != null) {
        BillScreen(session, billTable, onClose = { billSession = null }, onAddItems = { addItemsSession = session })
        return
    }
    if (showHistory) { HistoryScreen(onClose = { showHistory = false }); return }
    if (showCash) { CashCountScreen(onClose = { showCash = false }); return }
    if (showAddons && menuVm != null) { AddonGroupsScreen(menuVm, onBack = { showAddons = false }); return }
    if (showOffers && menuVm != null) { OffersScreen(menuVm, onBack = { showOffers = false }); return }

    val tabs = if (role == "owner") listOf("orders", "tables", "menu", "insights", "settings") else listOf("orders", "tables", "settings")
    val current = tabs[tab.coerceIn(0, tabs.lastIndex)]
    val openBill: (String, String) -> Unit = { id, label -> billSession = id; billTable = label }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val dismissSetupGuide = {
        setupGuidePreferences.edit().putBoolean("dismissed_$restaurantId", true).apply()
        showSetupGuide = false
    }

    if (showSetupGuide && role == "owner") {
        SetupGuideDialog(
            hasMenu = menuState.dishes.isNotEmpty(),
            tableCount = tablesState.tables.size,
            onAddMenu = { dismissSetupGuide(); tab = tabs.indexOf("menu") },
            onAddTables = { dismissSetupGuide(); tab = tabs.indexOf("tables") },
            onPrintQr = {
                dismissSetupGuide()
                tab = tabs.indexOf("tables")
                tablesVm.prepareQrSheet(context)
            },
            onDismiss = dismissSetupGuide,
        )
    }

    @Composable
    fun Badged(key: String) {
        if (key == "orders") BadgedBox(badge = { if (snap.attention > 0) Badge { Text("${snap.attention}") } }) { Icon(NAV.getValue(key).icon, null) }
        else Icon(NAV.getValue(key).icon, null)
    }

    val bar: @Composable () -> Unit = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.84f)) {
            tabs.forEachIndexed { i, k ->
                NavigationBarItem(selected = k == current, onClick = { tab = i }, icon = { Badged(k) }, label = { Text(NAV.getValue(k).label) })
            }
        }
    }

    val screen: @Composable (@Composable () -> Unit) -> Unit = { b ->
        when (current) {
            "orders" -> OrdersScreen(bottomBar = b, onOpenBill = openBill, status = hotelStatus)
            "tables" -> TablesScreen(tablesVm, bottomBar = b, onOpenBill = openBill, canManage = role == "owner")
            "menu" -> if (menuVm != null) MenuHost(
                menuVm, bottomBar = b,
                onAddonGroups = { showAddons = true },
                onOffers = { showOffers = true },
            )
            "insights" -> InsightsScreen(restaurantId = restaurantId, bottomBar = b, onHistory = { showHistory = true }, onCashCount = { showCash = true })
            else -> SettingsScreen(restaurantId = restaurantId, bottomBar = b, status = hotelStatus, role = role, onKitchenMode = { kitchenMode = it })
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(
                Modifier.fillMaxHeight().systemBarsPadding(),
                containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.84f),
            ) {
                tabs.forEachIndexed { i, k ->
                    NavigationRailItem(selected = k == current, onClick = { tab = i }, icon = { Badged(k) }, label = { Text(NAV.getValue(k).label) })
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { screen {} }
        }
    } else screen(bar)
}

@Composable
private fun SetupGuideDialog(
    hasMenu: Boolean,
    tableCount: Int,
    onAddMenu: () -> Unit,
    onAddTables: () -> Unit,
    onPrintQr: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Get your restaurant ready") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "Your restaurant details are submitted. Complete these steps to start taking QR orders.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SetupGuideStep(
                    number = 1,
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    complete = hasMenu,
                    title = "Add your menu",
                    detail = if (hasMenu) "Menu items are already set up." else "Create dishes or import them from a CSV.",
                    action = if (hasMenu) null else "Open menu",
                    onClick = onAddMenu,
                )
                SetupGuideStep(
                    number = 2,
                    icon = Icons.Default.TableRestaurant,
                    complete = tableCount > 0,
                    title = "Set up tables",
                    detail = if (tableCount > 0) "$tableCount ${if (tableCount == 1) "table" else "tables"} ready." else "Create a table for each place guests can sit.",
                    action = if (tableCount > 0) null else "Open tables",
                    onClick = onAddTables,
                )
                SetupGuideStep(
                    number = 3,
                    icon = Icons.Default.QrCode2,
                    complete = false,
                    title = "Print table QR codes",
                    detail = "Print and place one QR code at each table.",
                    action = "Create QR sheet",
                    onClick = onPrintQr,
                )
                SetupGuideStep(
                    number = 4,
                    icon = Icons.Default.Restaurant,
                    complete = false,
                    title = "Try the guest flow",
                    detail = "Scan a printed code with another phone and place a test order. Accept it from Orders.",
                    action = null,
                    onClick = {},
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("I’ll finish later") }
        },
    )
}

@Composable
private fun SetupGuideStep(
    number: Int,
    icon: ImageVector,
    complete: Boolean,
    title: String,
    detail: String,
    action: String?,
    onClick: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = MaterialTheme.shapes.medium,
                color = if (complete) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(Modifier.fillMaxSize(), androidx.compose.ui.Alignment.Center) {
                    Icon(
                        if (complete) Icons.Default.CheckCircle else icon,
                        contentDescription = null,
                        tint = if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("$number. $title", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (action != null) TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                    Text(action)
                }
            }
        }
    }
}
