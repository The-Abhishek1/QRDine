package com.xcloak.qrdine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.HotelRow
import com.xcloak.qrdine.data.PaymentRow
import java.time.LocalDate

/** Label, background and text colour for a hotel's state. */
@Composable
fun stateStyle(state: String): Triple<String, Color, Color> {
    val colors = MaterialTheme.colorScheme
    return when (state) {
        "pending" -> Triple("Pending approval", colors.tertiaryContainer, colors.onTertiaryContainer)
        "trial" -> Triple("Free trial", colors.secondaryContainer, colors.onSecondaryContainer)
        "active" -> Triple("Active", colors.primaryContainer, colors.onPrimaryContainer)
        "grace" -> Triple("Payment overdue", colors.errorContainer, colors.onErrorContainer)
        "expired" -> Triple("Expired", colors.errorContainer, colors.onErrorContainer)
        "hold" -> Triple("On hold", colors.surfaceVariant, colors.onSurfaceVariant)
        else -> Triple("Rejected", colors.errorContainer, colors.onErrorContainer)
    }
}

@Composable
fun StateChip(state: String) {
    val (label, bg, fg) = stateStyle(state)
    Text(label, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
private fun Kpi(label: String, value: String, modifier: Modifier, onClick: (() -> Unit)? = null) {
    GlassCard(modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformApp(vm: PlatformViewModel = viewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var hotelId by rememberSaveable { mutableStateOf<String?>(null) }
    var hotelFilter by rememberSaveable { mutableStateOf("all") }
    var approveFor by remember { mutableStateOf<HotelRow?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showPay by remember { mutableStateOf(false) }

    BackHandler(enabled = hotelId != null) { hotelId = null }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.messageShown() } }
    LaunchedEffect(ui.shareFile) { ui.shareFile?.let { QrSheet.share(context, it, "Export", "text/csv"); vm.shareConsumed() } }

    val viewing = ui.viewing
    if (viewing != null) { ViewHotelScreen(viewing, ui.viewingName) { vm.closeView() }; return }

    val open = hotelId?.let { id -> ui.hotels.firstOrNull { it.id == id } }
    LaunchedEffect(open, ui.loading) { if (hotelId != null && open == null && !ui.loading) hotelId = null }
    if (open != null) {
        HotelDetailScreen(open, ui, vm, snackbar) { hotelId = null }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(listOf("Dashboard", "Hotels", "Payments", "Settings")[tab], fontWeight = FontWeight.SemiBold) },
                actions = { IconButton({ vm.refresh() }) { Icon(Icons.Default.Refresh, "Refresh") } },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, icon = {
                    BadgedBox(badge = { if (ui.summary.pending > 0) Badge { Text("${ui.summary.pending}") } }) { Icon(Icons.Default.Home, null) }
                }, label = { Text("Dashboard") })
                NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("Hotels") })
                NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Default.Done, null) }, label = { Text("Payments") })
                NavigationBarItem(tab == 3, { tab = 3 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                tab == 0 -> DashboardTab(ui, onOpen = { hotelId = it }, onApprove = { approveFor = it }, onAdd = { showAdd = true }, onPay = { showPay = true },
                    onFilter = { hotelFilter = it; tab = 1 })
                tab == 1 -> HotelsTab(ui, hotelFilter, { hotelFilter = it }, onOpen = { hotelId = it }, onAdd = { showAdd = true }, onExport = { vm.exportHotels(context) })
                tab == 2 -> PaymentsTab(ui, onRecord = { showPay = true }, onExport = { rows -> vm.exportPayments(context, rows) })
                else -> PlatformSettingsTab(ui, vm)
            }
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }

    approveFor?.let { h -> ApproveDialog(h, { approveFor = null }) { days -> vm.approve(h, days) } }
    if (showAdd) AddHotelDialog({ showAdd = false }) { email, pw, name, phone, address, days -> vm.addHotel(email, pw, name, phone, address, days) }
    if (showPay) PaymentDialog(ui.hotels, null, { showPay = false }) { rid, amount, method, ref, months, date, note -> vm.recordPayment(rid, amount, method, ref, months, date, note) }
}

@Composable
private fun DashboardTab(
    ui: PlatformUi, onOpen: (String) -> Unit, onApprove: (HotelRow) -> Unit, onAdd: () -> Unit, onPay: () -> Unit, onFilter: (String) -> Unit,
) {
    val s = ui.summary
    val pending = ui.hotels.filter { it.state == "pending" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenIntro("PLATFORM OVERVIEW", "SaaS dashboard", "Review applications, monitor subscriptions, and track collections.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Waiting for approval", s.pending.toString(), Modifier.weight(1f)) { onFilter("pending") }
            Kpi("Ending within 7 days", s.expiringSoon.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Active (paid)", s.active.toString(), Modifier.weight(1f)) { onFilter("active") }
            Kpi("On free trial", s.trial.toString(), Modifier.weight(1f)) { onFilter("trial") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Overdue or expired", (s.grace + s.expired).toString(), Modifier.weight(1f)) { onFilter("due") }
            Kpi("On hold", s.hold.toString(), Modifier.weight(1f)) { onFilter("hold") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Collected this month", s.collectedMonth.rupees(), Modifier.weight(1f))
            Kpi("Collected in total", s.collectedTotal.rupees(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onAdd, Modifier.weight(1f)) { Text("Add hotel") }
            OutlinedButton(onPay, Modifier.weight(1f)) { Text("Record payment") }
        }
        if (pending.isNotEmpty()) {
            ScreenIntro("ACTION REQUIRED", "Waiting for approval", "${pending.size} hotel application(s) need a review.")
            pending.forEach { h ->
                GlassCard(Modifier.fillMaxWidth()) {
                        Text(h.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${h.ownerEmail ?: "–"} · applied ${dateLabel(h.createdAt)}", style = MaterialTheme.typography.bodySmall)
                        h.phone?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ onApprove(h) }) { Text("Approve") }
                            OutlinedButton({ onOpen(h.id) }) { Text("Review") }
                        }
                }
            }
        } else Text("No applications waiting.", style = MaterialTheme.typography.bodyMedium)
        RemindersSection(ui)
        InsightsSection(ui, onOpen)
    }
}

@Composable
private fun HotelsTab(ui: PlatformUi, filter: String, setFilter: (String) -> Unit, onOpen: (String) -> Unit, onAdd: () -> Unit, onExport: () -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val filters = listOf("all" to "All", "pending" to "Pending", "trial" to "Trial", "active" to "Active", "due" to "Overdue", "hold" to "On hold", "rejected" to "Rejected")
    val list = ui.hotels.filter { h ->
        val ok = when (filter) { "all" -> true; "due" -> h.state == "grace" || h.state == "expired"; else -> h.state == filter }
        ok && (q.isBlank() || h.name.contains(q, true) || (h.ownerEmail ?: "").contains(q, true))
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            ScreenIntro("CUSTOMER ACCOUNTS", "Hotels", "Search accounts, review their status, and manage access.")
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), label = { Text("Search name or email") }, singleLine = true)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            filters.forEach { (k, l) -> FilterChip(filter == k, { setFilter(k) }, label = { Text(l) }) }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${list.size} hotel(s)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onExport) { Text("Export CSV") }
            Button(onAdd) { Text("Add hotel") }
        }
        if (list.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No hotels match.") }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) { h ->
                GlassCard(Modifier.fillMaxWidth().clickable { onOpen(h.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(h.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                            StateChip(h.state)
                        }
                        Text(h.ownerEmail ?: "No owner login", style = MaterialTheme.typography.bodySmall)
                        Text(
                            when (h.state) {
                                "pending", "rejected" -> "Applied ${dateLabel(h.createdAt)}"
                                else -> "Paid through ${dateLabel(h.paidThrough)} · " + (h.lastOrderAt?.let { "last order ${ago(it)}" } ?: "no orders yet")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
            }
        }
    }
}

@Composable
private fun PaymentsTab(ui: PlatformUi, onRecord: () -> Unit, onExport: (List<PaymentRow>) -> Unit) {
    var range by rememberSaveable { mutableStateOf("month") }
    val today = LocalDate.now(IST)
    val rows = ui.payments.filter { p ->
        val d = runCatching { LocalDate.parse(p.paidOn) }.getOrNull() ?: return@filter true
        when (range) { "month" -> d.year == today.year && d.month == today.month; "30" -> !d.isBefore(today.minusDays(29)); else -> true }
    }
    val byMethod = listOf("cash" to "Cash", "upi" to "UPI", "bank" to "Bank").map { (k, l) -> l to rows.filter { it.method == k }.sumOf { it.amount } }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            ScreenIntro("REVENUE", "Payments", "Review subscription payments and issue receipts.")
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("month" to "This month", "30" to "30 days", "all" to "All time").forEach { (k, l) -> FilterChip(range == k, { range = k }, label = { Text(l) }) }
        }
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Collected ${rows.sumOf { it.amount }.rupees()}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${rows.size} payments · " + byMethod.joinToString("   ·   ") { "${it.first} ${it.second.rupees()}" }, style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton({ onExport(rows) }, enabled = rows.isNotEmpty()) { Text("Export CSV") }
            Button(onRecord) { Text("Record payment") }
        }
        if (rows.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No payments in this period.") }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.id }) { p -> PaymentRowView(p) { ReceiptButton(p, ui.support) } }
        }
    }
}

@Composable
fun PaymentRowView(p: PaymentRow, trailing: @Composable () -> Unit = {}) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(p.hotelName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("${dateLabel(p.paidOn)} · ${p.method.uppercase()} · ${p.months} mo" + (p.reference?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            }
            Text(p.amount.rupees(), fontWeight = FontWeight.Bold)
            trailing()
        }
    }
}

private fun actionLabel(a: String) = when (a) {
    "approve" -> "Approved"; "reject" -> "Rejected"; "hold" -> "Put on hold"; "unhold" -> "Hold released"; "update" -> "Details edited"
    "extend" -> "Access extended"; "set_dates" -> "Dates adjusted"; "payment" -> "Payment recorded"; "payment_removed" -> "Payment removed"
    "create_hotel" -> "Hotel added"; "reset_password" -> "Password reset"; "delete_hotel" -> "Hotel deleted"; "support_info" -> "Support details changed"
    else -> a
}

@Composable
private fun PlatformSettingsTab(ui: PlatformUi, vm: PlatformViewModel) {
    val auth: AuthViewModel = viewModel()
    var phone by remember(ui.support) { mutableStateOf(ui.support.phone.orEmpty()) }
    var email by remember(ui.support) { mutableStateOf(ui.support.email.orEmpty()) }
    var info by remember(ui.support) { mutableStateOf(ui.support.paymentInfo.orEmpty()) }
    var bizName by remember(ui.support) { mutableStateOf(ui.support.businessName.orEmpty()) }
    var bizAddr by remember(ui.support) { mutableStateOf(ui.support.businessAddress.orEmpty()) }
    var bizGst by remember(ui.support) { mutableStateOf(ui.support.businessGstin.orEmpty()) }
    var bc by remember { mutableStateOf("") }
    var bcDays by remember { mutableStateOf("7") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var confirmOut by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenIntro("PLATFORM CONTROLS", "Settings", "Keep support, receipts, and customer communications up to date.")
        ThemePreferenceCard()
        GlassCard(Modifier.fillMaxWidth()) {
            Text("SIGNED IN AS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(QrDineApp.container.auth.email() ?: "Account email unavailable", style = MaterialTheme.typography.bodyLarge)
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Text("APP DIAGNOSTICS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("Recent Android crashes and handled errors from signed-in hotel accounts. Reports are limited to error type, device/app version, app area, and stack-frame names.", style = MaterialTheme.typography.bodySmall)
            when {
                ui.diagnosticsUnavailable -> Text("Diagnostics are unavailable. Apply the diagnostics migration and deploy the app-diagnostics function.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                ui.diagnostics.isEmpty() -> Text("No reports in the last 30 days.", style = MaterialTheme.typography.bodySmall)
                else -> ui.diagnostics.take(50).forEachIndexed { index, report ->
                    if (index > 0) HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "${if (report.kind == "crash") "Crash" else "Error"} · ${report.errorType} · ${report.area}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            listOfNotNull(report.restaurantName, report.deviceModel, report.osVersion, "v${report.appVersion}")
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Occurred ${istTime(report.occurredAt, "dd MMM, hh:mm a")} · received ${istTime(report.receivedAt, "dd MMM, hh:mm a")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (report.frames.isNotEmpty()) {
                            Text(report.frames.joinToString("\n"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Text("SUPPORT & RECEIPTS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("Hotels see support and payment details when they need help or renewal.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(phone, { phone = it.take(30) }, label = { Text("Support phone") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, { email = it.trim().take(120) }, label = { Text("Support email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(info, { info = it.take(600) }, label = { Text("How hotels pay you (UPI ID, bank details)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            HorizontalDivider()
            Text("Printed on payment receipts", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(bizName, { bizName = it.take(120) }, label = { Text("Your business name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(bizAddr, { bizAddr = it.take(250) }, label = { Text("Business address") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(bizGst, { bizGst = it.uppercase().filter(Char::isLetterOrDigit).take(15) }, label = { Text("GSTIN (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button({ vm.saveSupport(phone, email, info, bizName, bizAddr, bizGst) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Save details") }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Text("MESSAGE TO EVERY HOTEL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("Show an announcement in every hotel's Orders screen. It expires automatically.", style = MaterialTheme.typography.bodySmall)
            if (!ui.support.broadcast.isNullOrBlank()) Text("Showing now: ${ui.support.broadcast}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(bc, { bc = it.take(400) }, label = { Text("Message") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(bcDays, { bcDays = it.filter(Char::isDigit).take(2) }, label = { Text("Show for (days)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button({ vm.broadcast(bc, bcDays.toIntOrNull() ?: 7); bc = "" }, enabled = !ui.busy && (bc.isNotBlank() || !ui.support.broadcast.isNullOrBlank()) && (bcDays.toIntOrNull() ?: 7) in 1..60, modifier = Modifier.fillMaxWidth()) { Text(if (bc.isBlank()) "Clear the message" else "Send to all hotels") }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Text("YOUR ACCOUNT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(ui.email ?: "–", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(pw, { pw = it }, label = { Text("New password (8+)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pw2, { pw2 = it }, label = { Text("Repeat new password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                isError = pw2.isNotEmpty() && pw != pw2)
            Button({ vm.changePassword(pw); pw = ""; pw2 = "" }, enabled = !ui.busy && pw.length >= 8 && pw == pw2, modifier = Modifier.fillMaxWidth()) { Text("Change password") }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Text("RECENT ACTIVITY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (ui.activity.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
            ui.activity.take(25).forEach { a ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(actionLabel(a.action), fontWeight = FontWeight.Medium)
                        a.hotelName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    Text(istTime(a.createdAt, "dd MMM, hh:mm a"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        OutlinedButton({ confirmOut = true }, Modifier.fillMaxWidth()) { Text("Sign out") }
        Text("The platform admin account is created by you in the Supabase dashboard (see the backend README).", style = MaterialTheme.typography.bodySmall)
    }
    if (confirmOut) AlertDialog(
        onDismissRequest = { confirmOut = false }, title = { Text("Sign out?") },
        confirmButton = { TextButton({ confirmOut = false; auth.signOut() }) { Text("Sign out") } },
        dismissButton = { TextButton({ confirmOut = false }) { Text("Cancel") } },
    )
}
