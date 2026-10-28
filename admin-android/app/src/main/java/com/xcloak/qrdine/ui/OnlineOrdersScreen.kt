package com.xcloak.qrdine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xcloak.qrdine.Diagnostics
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.OnlineOrder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineOrdersScreen(bottomBar: @Composable () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var orders by remember { mutableStateOf<List<OnlineOrder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busyOrder by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var rejectFor by remember { mutableStateOf<OnlineOrder?>(null) }
    var rejectReason by remember { mutableStateOf("") }
    var etaText by remember { mutableStateOf("30") }

    suspend fun refresh() {
        try {
            orders = QrDineApp.container.onlineOrders.orders()
            error = null
        } catch (e: Exception) {
            Diagnostics.record(QrDineApp.appContext, e, "online-orders")
            error = "Online orders could not be loaded. Check your connection and backend setup."
        } finally {
            loading = false
        }
    }

    fun act(order: OnlineOrder, action: String, reason: String? = null, eta: Int? = null) {
        scope.launch {
            busyOrder = order.id
            try {
                QrDineApp.container.onlineOrders.action(order.id, action, reason, eta)
                refresh()
            } catch (e: Exception) {
                Diagnostics.record(QrDineApp.appContext, e, "online-order-action")
                error = when {
                    "payment_not_verified" in e.message.orEmpty() -> "Verify the UPI transfer before accepting this order."
                    "online_order_not_pending" in e.message.orEmpty() -> "This order is no longer waiting for a decision."
                    else -> "Could not update the order. Check your connection and try again."
                }
            } finally {
                busyOrder = null
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(20_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Online orders", fontWeight = FontWeight.SemiBold) },
                actions = { IconButton(onClick = { scope.launch { refresh() } }) { Icon(Icons.Default.Refresh, "Refresh") } },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Orders placed through the customer app. Table QR orders remain in the Orders tab.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (loading) CircularProgressIndicator()
            else if (orders.isEmpty()) Text("No online orders yet.", style = MaterialTheme.typography.bodyLarge)
            else LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(orders, key = { it.id }) { order ->
                    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${order.customerName} · ${order.customerPhone}", fontWeight = FontWeight.SemiBold)
                                Text(order.status.replace('_', ' ').uppercase(), style = MaterialTheme.typography.labelMedium)
                            }
                            Text("${order.fulfilment.uppercase()} · ${order.paymentMethod.uppercase()} · ${order.paymentStatus.replace('_', ' ')}")
                            if (order.deliveryAddress != null) Text("${order.deliveryAddress}, ${order.postalCode}")
                            order.items.forEach { item ->
                                val addons = item.addons.joinToString { it.name }
                                Text(
                                    "${item.qty} × ${item.name}" +
                                        (item.variantName?.let { " · $it" } ?: "") +
                                        (addons.takeIf(String::isNotBlank)?.let { " · $it" } ?: "") +
                                        " · ${item.unitPrice.rupees()}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                item.instructions?.takeIf(String::isNotBlank)?.let { Text("Note: $it", style = MaterialTheme.typography.bodySmall) }
                            }
                            Text(
                                "Items ${order.subtotal.rupees()} · Delivery ${order.deliveryFee.rupees()} · COD ${order.codFee.rupees()} · Total ${order.total.rupees()}",
                                fontWeight = FontWeight.Bold,
                            )
                            order.paymentReference?.let { Text("UPI reference: $it", style = MaterialTheme.typography.bodySmall) }
                            if (order.rejectReason != null) Text("Reason: ${order.rejectReason}", color = MaterialTheme.colorScheme.error)
                            if (order.status == "pending") {
                                if (order.paymentMethod == "upi" && order.paymentStatus == "verification_required") {
                                    OutlinedButton(
                                        onClick = { act(order, "verify_payment") },
                                        enabled = busyOrder == null,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("Verify UPI payment") }
                                }
                                OutlinedTextField(
                                    value = etaText,
                                    onValueChange = { etaText = it.filter(Char::isDigit).take(3) },
                                    label = { Text("Preparation time (minutes)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(
                                    onClick = { act(order, "accept", eta = etaText.toIntOrNull()) },
                                    enabled = busyOrder == null && (order.paymentMethod == "cod" || order.paymentStatus == "paid") &&
                                        (etaText.toIntOrNull() ?: 0) in 1..240,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(if (order.paymentMethod == "upi" && order.paymentStatus != "paid") "Verify payment before accepting" else "Accept order") }
                                TextButton(
                                    onClick = { rejectFor = order; rejectReason = "" },
                                    enabled = busyOrder == null,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Reject order") }
                            } else if (order.status == "accepted") {
                                Button(onClick = { act(order, "complete") }, enabled = busyOrder == null, modifier = Modifier.fillMaxWidth()) {
                                    Text("Mark completed")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    rejectFor?.let { order ->
        AlertDialog(
            onDismissRequest = { rejectFor = null },
            title = { Text("Reject this online order?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Reserved dish stock will be restored. For paid UPI orders, refund the customer separately.")
                    OutlinedTextField(
                        value = rejectReason,
                        onValueChange = { rejectReason = it.take(200) },
                        label = { Text("Reason") },
                        minLines = 2,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { act(order, "reject", rejectReason); rejectFor = null },
                    enabled = rejectReason.isNotBlank() && busyOrder == null,
                ) { Text("Reject order") }
            },
            dismissButton = { TextButton(onClick = { rejectFor = null }) { Text("Cancel") } },
        )
    }
}
