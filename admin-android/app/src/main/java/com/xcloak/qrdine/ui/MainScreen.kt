package com.xcloak.qrdine.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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

    // keyed by restaurant so another login never reuses the previous restaurant's state
    val menuVm: MenuViewModel? = if (role == "owner") viewModel<MenuViewModel>(key = "menu-$restaurantId", factory = MenuViewModel.Factory(restaurantId)) else null
    val tablesVm: TablesViewModel = viewModel(key = "tables-$restaurantId", factory = TablesViewModel.Factory(restaurantId))

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(restaurantId) {
        store.start(restaurantId)
        Fcm.registerIfPossible(context)
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

    val tabs = if (role == "owner") listOf("orders", "tables", "menu", "insights", "settings") else listOf("orders", "tables", "settings")
    val current = tabs[tab.coerceIn(0, tabs.lastIndex)]
    val openBill: (String, String) -> Unit = { id, label -> billSession = id; billTable = label }
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    @Composable
    fun Badged(key: String) {
        if (key == "orders") BadgedBox(badge = { if (snap.attention > 0) Badge { Text("${snap.attention}") } }) { Icon(NAV.getValue(key).icon, null) }
        else Icon(NAV.getValue(key).icon, null)
    }

    val bar: @Composable () -> Unit = {
        NavigationBar {
            tabs.forEachIndexed { i, k ->
                NavigationBarItem(selected = k == current, onClick = { tab = i }, icon = { Badged(k) }, label = { Text(NAV.getValue(k).label) })
            }
        }
    }

    val screen: @Composable (@Composable () -> Unit) -> Unit = { b ->
        when (current) {
            "orders" -> OrdersScreen(bottomBar = b, onOpenBill = openBill, status = hotelStatus)
            "tables" -> TablesScreen(tablesVm, bottomBar = b, onOpenBill = openBill, canManage = role == "owner")
            "menu" -> if (menuVm != null) MenuHost(menuVm, bottomBar = b, onAddonGroups = { showAddons = true })
            "insights" -> InsightsScreen(bottomBar = b, onHistory = { showHistory = true }, onCashCount = { showCash = true })
            else -> SettingsScreen(bottomBar = b, status = hotelStatus, role = role, onKitchenMode = { kitchenMode = it })
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(Modifier.fillMaxHeight().systemBarsPadding()) {
                tabs.forEachIndexed { i, k ->
                    NavigationRailItem(selected = k == current, onClick = { tab = i }, icon = { Badged(k) }, label = { Text(NAV.getValue(k).label) })
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { screen {} }
        }
    } else screen(bar)
}
