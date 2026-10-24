package com.xcloak.qrdine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.Bill
import com.xcloak.qrdine.data.BillItem
import com.xcloak.qrdine.data.CloseOutcome
import com.xcloak.qrdine.data.InvoiceRow
import com.xcloak.qrdine.print.Escpos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun friendlyBill(e: Exception): String {
    val m = e.message ?: return "Something went wrong"
    return when {
        "orders_pending" in m -> "A waiting order must be accepted or rejected first (Orders tab)."
        "coupon_invalid" in m -> "That coupon is not valid for this bill."
        "split_mismatch" in m -> "The cash and UPI amounts must add up to the total."
        "nothing_to_bill" in m -> "There is nothing to bill yet."
        "session_closed" in m || "session_not_found" in m -> "This session is already closed, or you are not allowed to bill it."
        "reason_required" in m -> "Please give a reason."
        "item_cancelled" in m -> "That item was already cancelled."
        else -> m.take(160)
    }
}

private fun pinMessage(code: String) = when (code) {
    "pin_required" -> "Enter the manager PIN to give a discount."
    "pin_wrong" -> "That PIN is wrong."
    "pin_locked" -> "Too many wrong PINs. Discounts are locked for 10 minutes."
    "pin_not_set" -> "No manager PIN is set. The owner can set one in Settings, Manager PIN."
    else -> "Could not apply the discount."
}

private fun statusLabel(s: String) = when (s) { "pending" -> "Waiting"; "accepted" -> "Cooking"; "completed" -> "Served"; else -> s }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillScreen(sessionId: String, tableLabel: String, onClose: () -> Unit, onAddItems: () -> Unit) {
    val context = LocalContext.current
    val container = QrDineApp.container
    val owner = container.owner
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var bill by remember { mutableStateOf<Bill?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var couponInput by rememberSaveable { mutableStateOf("") }
    var applied by rememberSaveable { mutableStateOf<String?>(null) }
    var couponError by remember { mutableStateOf<String?>(null) }
    var method by rememberSaveable { mutableStateOf("cash") }
    var cashText by rememberSaveable { mutableStateOf("") }
    var manualOn by rememberSaveable { mutableStateOf(false) }
    var manualPercent by rememberSaveable { mutableStateOf(false) }
    var manualText by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf(false) }
    var payError by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<InvoiceRow?>(null) }
    var showSplit by remember { mutableStateOf(false) }
    var cancelFor by remember { mutableStateOf<BillItem?>(null) }
    BackHandler(onBack = onClose)

    val manualPaise: Long = when {
        !manualOn -> 0L
        manualPercent -> ((bill?.subtotal ?: 0L) * (manualText.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 100.0) / 100.0).toLong()
        else -> rupeesToPaise(manualText) ?: 0L
    }

    suspend fun load() {
        try {
            bill = owner.bill(sessionId, applied, manualPaise)
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val m = e.message ?: ""
            if ("coupon_invalid" in m) { couponError = "That coupon is not valid for this bill."; applied = null }
            else loadError = friendlyBill(e)
        }
    }
    LaunchedEffect(sessionId, applied, manualPaise) { while (done == null) { load(); delay(8000) } }

    fun shareInvoice(row: InvoiceRow) {
        scope.launch {
            val file = withContext(Dispatchers.Default) { InvoicePdf.build(context, row) }
            QrSheet.share(context, file, "Share or print the bill")
        }
    }

    val b = bill
    val finished = done
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Table $tableLabel", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        if (finished != null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Payment recorded", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("${finished.snapshot.bill.total.rupees()} by ${finished.snapshot.method.uppercase()}", style = MaterialTheme.typography.titleMedium)
                Text("Bill ${InvoicePdf.fileLabel(finished)}. Table $tableLabel is closed and the guest has been thanked.", style = MaterialTheme.typography.bodyMedium)
                Button({ shareInvoice(finished) }, Modifier.fillMaxWidth().height(50.dp)) { Text("Share or print the bill (PDF)") }
                if (Escpos.hasPrinter(context)) OutlinedButton({
                    scope.launch { try { Escpos.printBill(context, finished) } catch (e: Exception) { snackbar.showSnackbar("Could not print: ${(e.message ?: "printer error").take(100)}") } }
                }, Modifier.fillMaxWidth()) { Text("Print on the receipt printer") }
                OutlinedButton(onClose, Modifier.fillMaxWidth()) { Text("Done") }
                Text("Tap Open table on the Tables tab when new guests sit down.", style = MaterialTheme.typography.bodySmall)
            }
            return@Scaffold
        }
        if (b == null) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                if (loadError != null) Text(loadError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp)) else CircularProgressIndicator()
            }
            return@Scaffold
        }

        val hasPending = b.orders.any { it.status == "pending" }
        val cooking = b.orders.count { it.status == "accepted" }
        val cashPaise = rupeesToPaise(cashText)
        val splitOk = method != "split" || (cashPaise != null && cashPaise in 0..b.total)
        val open = b.status == "active" || b.status == "bill_requested"

        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Seated ${ago(b.openedAt)}", style = MaterialTheme.typography.bodySmall)
            loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onAddItems, Modifier.weight(1f)) { Text("Add items") }
                OutlinedButton({ showSplit = true }, Modifier.weight(1f)) { Text("Split bill") }
            }

            Text("Orders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (b.orders.isEmpty()) Text("No orders yet.")
            b.orders.forEachIndexed { i, o ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Order ${i + 1} · ${if (o.type == "parcel") "Parcel" else "Eat here"}${if (o.source == "staff") " · staff" else ""}", fontWeight = FontWeight.SemiBold)
                            Text(if (o.status == "accepted" && o.ready) "Ready" else statusLabel(o.status), color = if (o.status == "completed") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
                        }
                        o.items.forEach {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${it.qty} × ${it.name}" + if (it.cancelled) "  (cancelled${it.reason?.let { r -> ": $r" } ?: ""})" else "",
                                    Modifier.weight(1f),
                                    color = if (it.cancelled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                )
                                Text((it.qty.toLong() * it.price).rupees())
                                if (!it.cancelled && open) TextButton({ cancelFor = it }) { Text("Cancel") }
                            }
                        }
                    }
                }
            }

            Text("Bill", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    b.lines.forEach {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${it.qty} × ${it.name}", Modifier.weight(1f)); Text(it.amount.rupees()) }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    BillRow("Subtotal", b.subtotal.rupees())
                    if (b.couponDiscount > 0) BillRow("Coupon${b.coupon?.let { " ($it)" }.orEmpty()}", "-${b.couponDiscount.rupees()}")
                    if (b.manualDiscount > 0) BillRow("Manager discount", "-${b.manualDiscount.rupees()}")
                    if (b.serviceCharge > 0) BillRow("Service charge (${b.serviceChargePct}%)", b.serviceCharge.rupees())
                    if (b.tax > 0) { BillRow("CGST (${b.gstRate / 2}%)", b.cgst.rupees()); BillRow("SGST (${b.gstRate / 2}%)", b.sgst.rupees()) }
                    if (b.roundOff != 0L) BillRow("Round off", (if (b.roundOff < 0) "-" else "") + kotlin.math.abs(b.roundOff).rupees())
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(b.total.rupees(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (b.coupon != null && applied == null && b.couponDiscount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Coupon ${b.coupon} was entered by the guest.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton({ scope.launch { try { owner.clearGuestCoupon(sessionId); load() } catch (e: Exception) { snackbar.showSnackbar(friendlyBill(e)) } } }) { Text("Remove") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    couponInput, { couponInput = it.uppercase().take(20); couponError = null }, label = { Text("Coupon code") }, singleLine = true,
                    modifier = Modifier.weight(1f), isError = couponError != null,
                    supportingText = { Text(couponError ?: (if (applied != null) "Applied: $applied" else "")) },
                )
                if (applied != null) TextButton({ applied = null; couponInput = "" }) { Text("Remove") }
                else TextButton({ applied = couponInput.trim().ifBlank { null } }, enabled = couponInput.isNotBlank()) { Text("Apply") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Give a discount (needs the manager PIN)", Modifier.weight(1f))
                Switch(manualOn, { manualOn = it; if (!it) { manualText = ""; pin = "" } })
            }
            if (manualOn) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!manualPercent, { manualPercent = false }, label = { Text("₹ amount") })
                    FilterChip(manualPercent, { manualPercent = true }, label = { Text("% of bill") })
                }
                OutlinedTextField(
                    manualText, { manualText = it.filter { c -> c.isDigit() || c == '.' }.take(7) }, label = { Text(if (manualPercent) "Percent" else "Amount (₹)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text("Manager PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth(),
                )
            }

            Text("Payment received by", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("cash" to "Cash", "upi" to "UPI", "split" to "Cash + UPI").forEach { (k, label) -> FilterChip(method == k, { method = k }, label = { Text(label) }) }
            }
            if (method == "split") {
                OutlinedTextField(
                    cashText, { cashText = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, label = { Text("Cash part (₹)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(if (splitOk && cashPaise != null) "UPI part: ${(b.total - cashPaise).rupees()}" else "Enter an amount up to ${b.total.rupees()}") },
                )
            }
            if (method == "upi") Text("Check the credit in your own UPI app before marking paid.", style = MaterialTheme.typography.bodySmall)

            if (hasPending) Text("A waiting order must be accepted or rejected first (Orders tab).", color = MaterialTheme.colorScheme.error)
            else if (cooking > 0) Text("$cooking order(s) still cooking will be marked served.", style = MaterialTheme.typography.bodySmall)
            payError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                { payError = null; confirm = true },
                enabled = !paying && !hasPending && splitOk && b.total > 0 && (!manualOn || manualPaise == 0L || pin.length >= 4),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { if (paying) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Mark paid · ${b.total.rupees()}") }
            Spacer(Modifier.height(24.dp))
        }

        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Payment received?") },
                text = { Text("Confirm you received ${b.total.rupees()} (${when (method) { "cash" -> "cash"; "upi" -> "UPI"; else -> "cash + UPI" }}). This closes the table and issues the bill.") },
                confirmButton = {
                    TextButton({
                        confirm = false; paying = true
                        scope.launch {
                            try {
                                val upiPart = if (method == "split") b.total - (cashPaise ?: 0L) else null
                                when (val r = owner.closeSession(sessionId, method, if (method == "split") cashPaise else null, upiPart, applied, manualPaise, pin)) {
                                    is CloseOutcome.Done -> { done = owner.invoice(r.result.invoiceId); container.ordersStore.refresh() }
                                    is CloseOutcome.Failed -> payError = pinMessage(r.code)
                                }
                            } catch (e: Exception) { payError = friendlyBill(e) }
                            finally { paying = false }
                        }
                    }) { Text("Yes, mark paid") }
                },
                dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
            )
        }

        cancelFor?.let { item ->
            var reason by remember(item) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { cancelFor = null },
                title = { Text("Cancel ${item.qty} × ${item.name}?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("It comes off the bill and the stock is given back.")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Changed mind", "Wrong item", "Sold out").forEach { r -> FilterChip(reason == r, { reason = r }, label = { Text(r) }) }
                        }
                        OutlinedTextField(reason, { reason = it.take(120) }, label = { Text("Reason") }, singleLine = true)
                    }
                },
                confirmButton = {
                    TextButton(enabled = reason.isNotBlank(), onClick = {
                        val id = item.id
                        cancelFor = null
                        scope.launch { try { container.orders.cancelItem(id, reason); container.ordersStore.refresh(); load() } catch (e: Exception) { snackbar.showSnackbar(friendlyBill(e)) } }
                    }) { Text("Cancel item") }
                },
                dismissButton = { TextButton({ cancelFor = null }) { Text("Keep") } },
            )
        }
        if (showSplit) SplitDialog(b) { showSplit = false }
    }
}

@Composable
private fun BillRow(left: String, right: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(left); Text(right) }
}

/** A calculator for sharing the bill. The payment is still recorded as one bill (cash, UPI or both). */
@Composable
private fun SplitDialog(b: Bill, onDismiss: () -> Unit) {
    var byDish by remember { mutableStateOf(false) }
    var guests by remember { mutableIntStateOf(2) }
    val assign = remember { mutableStateMapOf<Int, Int>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Split ${b.total.rupees()}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!byDish, { byDish = false }, label = { Text("Equally") })
                    FilterChip(byDish, { byDish = true }, label = { Text("By dishes") })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Guests")
                    OutlinedButton({ if (guests > 2) guests-- }) { Text("−") }
                    Text("$guests", fontWeight = FontWeight.Bold)
                    OutlinedButton({ if (guests < 6) guests++ }) { Text("+") }
                }
                if (!byDish) {
                    val each = (b.total + guests - 1) / guests
                    Text("Each pays ${each.rupees()}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("The last guest pays ${(b.total - each * (guests - 1)).rupees()} so the total matches.", style = MaterialTheme.typography.bodySmall)
                } else {
                    b.lines.forEachIndexed { i, l ->
                        Text("${l.qty} × ${l.name}  ${l.amount.rupees()}", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (g in 0 until guests) FilterChip((assign[i] ?: 0) == g, { assign[i] = g }, label = { Text("${g + 1}") })
                        }
                    }
                    HorizontalDivider()
                    for (g in 0 until guests) {
                        val sum = b.lines.withIndex().filter { (assign[it.index] ?: 0) == g }.sumOf { it.value.amount }
                        val share = if (b.subtotal > 0) Math.round(sum.toDouble() * b.total / b.subtotal) else 0L
                        Text("Guest ${g + 1} pays ${share.rupees()}", fontWeight = FontWeight.SemiBold)
                    }
                    Text("Tax, service charge and discounts are shared in proportion. Rounding can differ by a rupee.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done") } },
    )
}
