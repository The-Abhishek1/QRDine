package com.xcloak.qrdine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.xcloak.qrdine.data.HotelStatus
import com.xcloak.qrdine.data.label
import com.xcloak.qrdine.notify.Prefs
import com.xcloak.qrdine.print.Escpos
import com.xcloak.qrdine.data.OrderCard
import com.xcloak.qrdine.data.OnlineOrder
import com.xcloak.qrdine.data.RequestCard
import com.xcloak.qrdine.data.ServingRow
import kotlinx.coroutines.delay

@Composable
fun TableTag(label: String) {
    Text(
        label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(
    bottomBar: @Composable () -> Unit,
    onOpenBill: (String, String) -> Unit,
    status: HotelStatus? = null,
    onOpenOnlineOrders: () -> Unit = {},
    vm: OrdersViewModel = viewModel(),
) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var acceptFor by remember { mutableStateOf<OrderCard?>(null) }
    var rejectFor by remember { mutableStateOf<OrderCard?>(null) }
    val tick by produceState(0L) { while (true) { delay(30_000); value = System.currentTimeMillis() } }

    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.messageShown() } }

    Scaffold(
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Orders", fontWeight = FontWeight.SemiBold) }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (snap.isOpen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(if (snap.isOpen) "Accepting orders" else "Orders paused", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (snap.isOpen) "Guests can place orders now."
                            else "Turn this on when you are ready to serve.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(snap.isOpen, { vm.setOpen(it) }, enabled = snap.loaded && !ui.busy)
                }
            }
            SubscriptionBanner(status)
            BroadcastBanner(status)
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            snap.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }

            val action = snap.attention + snap.accepted.size
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("New orders", snap.pending.size.toString(), Modifier.weight(1f))
                MetricCard("Cooking", snap.accepted.size.toString(), Modifier.weight(1f))
                MetricCard("Tables in service", snap.serving.size.toString(), Modifier.weight(1f))
            }
            TabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 4.dp)) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Needs action" + if (action > 0) " ($action)" else "") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Serving (${snap.serving.size})") })
            }

            if (tab == 0) {
                if (snap.loaded && action == 0) {
                    EmptyState("No orders right now", "New orders appear here with a loud alert.", Icons.Default.Restaurant)
                } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (snap.attention > 0) item { Text("Needs you now (${snap.attention})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    items(snap.requests, key = { "r-${it.req.id}" }) { r ->
                        RequestRow(r, tick, ui.busy, onOpenBill = { onOpenBill(r.req.sessionId, r.tableLabel) }, onDone = { vm.resolve(r.req.id) })
                    }
                    items(snap.pending, key = { "p-${it.order.id}" }) { o ->
                        OrderCardView(o, tick) {
                            Button({ acceptFor = o }, enabled = !ui.busy, modifier = Modifier.weight(1f)) { Text("Accept") }
                            OutlinedButton({ rejectFor = o }, enabled = !ui.busy, modifier = Modifier.weight(1f)) { Text("Reject") }
                        }
                    }
                    if (snap.pendingOnlineOrders.isNotEmpty()) {
                        item { Text("Online orders (${snap.pendingOnlineOrders.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                        items(snap.pendingOnlineOrders, key = { "online-${it.id}" }) { order ->
                            PendingOnlineOrderCard(order, onOpenOnlineOrders)
                        }
                    }
                    if (snap.accepted.isNotEmpty()) item { Text("Cooking (${snap.accepted.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                    items(snap.accepted, key = { "a-${it.order.id}" }) { o ->
                        OrderCardView(o, tick) {
                            if (o.order.readyAt == null) FilledTonalButton({ vm.ready(o.order.id) }, enabled = !ui.busy, modifier = Modifier.weight(1f)) { Text("Ready") }
                            Button({ vm.served(o.order.id) }, enabled = !ui.busy, modifier = Modifier.weight(1f)) { Text("Mark served") }
                            if (Escpos.hasPrinter(context)) TextButton({ vm.print(o) }) { Text("Print") }
                        }
                    }
                }
            } else {
                if (snap.loaded && snap.serving.isEmpty()) {
                    EmptyState("No tables in service", "Open a table on the Tables tab. Served orders stay here, with the running bill, until you mark the bill paid.", Icons.Default.TableRestaurant)
                } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(snap.serving, key = { it.sessionId }) { s -> ServingCard(s, tick) { onOpenBill(s.sessionId, s.table) } }
                }
            }
        }
    }

    acceptFor?.let { o ->
        var eta by remember { mutableIntStateOf(15) }
        var custom by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { acceptFor = null },
            title = { Text("Accept order from ${o.tableLabel}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("How long will it take? The guest sees this time.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 15, 20, 30).forEach { m -> FilterChip(custom.isEmpty() && eta == m, { eta = m; custom = "" }, label = { Text("$m min") }) }
                    }
                    OutlinedTextField(
                        custom, { custom = it.filter(Char::isDigit).take(3) }, label = { Text("Other (minutes)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = {
                val minutes = custom.toIntOrNull() ?: eta
                TextButton(enabled = minutes in 1..240, onClick = { vm.accept(o, minutes); acceptFor = null }) { Text("Accept") }
            },
            dismissButton = { TextButton({ acceptFor = null }) { Text("Cancel") } },
        )
    }

    rejectFor?.let { o ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rejectFor = null },
            title = { Text("Reject order from ${o.tableLabel}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("The guest sees the reason.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Sold out", "Kitchen closed", "Too busy").forEach { r -> FilterChip(reason == r, { reason = r }, label = { Text(r) }) }
                    }
                    OutlinedTextField(reason, { reason = it.take(120) }, label = { Text("Reason") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(enabled = reason.isNotBlank(), onClick = { vm.reject(o.order.id, reason); rejectFor = null }) { Text("Reject") } },
            dismissButton = { TextButton({ rejectFor = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyState(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        IllustratedEmptyState(title, body, icon = icon)
    }
}

@Composable
private fun ServingCard(s: ServingRow, tick: Long, onOpen: () -> Unit) {
    val bill = s.status == "bill_requested"
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = if (bill) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TableTag(s.table)
                Column(Modifier.weight(1f)) {
                    Text("${s.items} items · ${s.total.rupees()}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("Seated ${ago(s.openedAt).also { tick.hashCode() }}", style = MaterialTheme.typography.bodySmall)
                }
                if (bill) Text("Bill requested", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            }
            Text(
                listOfNotNull(
                    s.pending.takeIf { it > 0 }?.let { "$it waiting" },
                    s.cooking.takeIf { it > 0 }?.let { "$it cooking" },
                    s.served.takeIf { it > 0 }?.let { "$it served" },
                ).joinToString("  ·  ").ifBlank { "No orders yet" },
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onOpen, Modifier.fillMaxWidth()) { Text(if (bill) "Collect payment" else "View dishes and bill") }
        }
    }
}

@Composable
private fun PendingOnlineOrderCard(order: OnlineOrder, onOpen: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(order.customerName, fontWeight = FontWeight.SemiBold)
                    Text("Online · ${order.fulfilment.replace('_', ' ')} · ${order.id.take(8)}", style = MaterialTheme.typography.bodySmall)
                }
                Text(order.total.toLong().rupees(), fontWeight = FontWeight.Bold)
            }
            Text("${order.paymentMethod.uppercase()} · ${order.paymentStatus.replace('_', ' ')} · ${ago(order.createdAt)}", style = MaterialTheme.typography.bodySmall)
            order.items.forEach { item ->
                Text("${item.qty} × ${item.name}" + (item.variantName?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            }
            order.customerNote?.takeIf(String::isNotBlank)?.let { Text("Note: $it", style = MaterialTheme.typography.bodySmall) }
            order.deliveryAddress?.let { Text("Delivery: $it, ${order.postalCode.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Review and respond to online order") }
        }
    }
}

@Composable
private fun RequestRow(r: RequestCard, tick: Long, busy: Boolean, onOpenBill: () -> Unit, onDone: () -> Unit) {
    val bill = r.req.type == "bill"
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TableTag(r.tableLabel)
            Column(Modifier.weight(1f)) {
                Text(if (bill) "Wants the bill" else "Calling a waiter", fontWeight = FontWeight.SemiBold)
                Text(ago(r.req.createdAt).also { tick.hashCode() }, style = MaterialTheme.typography.bodySmall)
            }
            if (bill) Button(onOpenBill) { Text("Open bill") } else Button(onDone, enabled = !busy) { Text("Done") }
        }
    }
}

@Composable
private fun OrderCardView(o: OrderCard, tick: Long, actions: @Composable RowScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TableTag(o.tableLabel)
                Column(Modifier.weight(1f)) {
                    Text(if (o.order.orderType == "parcel") "Parcel" else "Eat here", fontWeight = FontWeight.SemiBold)
                    Text(ago(o.order.createdAt).also { tick.hashCode() }, style = MaterialTheme.typography.bodySmall)
                }
                Text(o.total.toLong().rupees(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            o.items.filter { !it.cancelled }.forEach { item ->
                Text("${item.qty} × ${item.label()}")
                item.instructions?.let { n -> Text("  $n", style = MaterialTheme.typography.bodySmall) }
            }
            o.order.note?.let { Text("Note: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            if (o.order.status == "accepted" && o.order.etaMinutes != null) Text("Promised in ${o.order.etaMinutes} min", style = MaterialTheme.typography.bodySmall)
            if (o.order.readyAt != null) Text("Ready for pickup", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            if (o.order.source == "staff") Text("Entered by staff", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), content = actions)
        }
    }
}

/** Warns the owner before ordering stops: trial or subscription ending soon, overdue, or ended. */
@Composable
fun SubscriptionBanner(s: HotelStatus?) {
    if (s == null) return
    val left = s.daysLeft ?: 99
    val msg = when {
        s.state == "expired" -> "Your subscription has ended. Guests can't order until you renew."
        s.state == "grace" -> "Payment overdue. Ordering will stop soon. Please renew."
        (s.state == "trial" || s.state == "active") && left <= 7 ->
            (if (s.state == "trial") "Your free trial" else "Your subscription") + " ends " + (if (left <= 0) "today." else if (left == 1) "tomorrow." else "in $left days.")
        else -> return
    }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(msg, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        val contact = listOfNotNull(s.support.phone, s.support.email).joinToString(" · ")
        if (contact.isNotBlank()) Text("To renew, contact $contact", style = MaterialTheme.typography.bodySmall)
        s.support.paymentInfo?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** A message from the platform owner (maintenance, new feature...). Hidden once dismissed on this phone. */
@Composable
fun BroadcastBanner(s: HotelStatus?) {
    val ctx = LocalContext.current
    var dismissed by remember { mutableLongStateOf(Prefs.dismissedBroadcast(ctx)) }
    val b = s?.broadcast ?: return
    if (b.id <= dismissed) return
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(b.message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton({ Prefs.setDismissedBroadcast(ctx, b.id); dismissed = b.id }) { Text("Dismiss") }
    }
}
