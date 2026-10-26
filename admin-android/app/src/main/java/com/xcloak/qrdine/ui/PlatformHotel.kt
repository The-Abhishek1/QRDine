package com.xcloak.qrdine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xcloak.qrdine.data.HotelRow
import com.xcloak.qrdine.data.PaymentRow

@Composable
private fun Info(label: String, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value?.takeIf { it.isNotBlank() } ?: "–", Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotelDetailScreen(h: HotelRow, ui: PlatformUi, vm: PlatformViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }
    var removePayment by remember { mutableStateOf<PaymentRow?>(null) }
    val payments = ui.payments.filter { it.restaurantId == h.id }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(h.name, maxLines = 1, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            GlassCard(Modifier.fillMaxWidth()) {
                Text("HOTEL ACCOUNT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StateChip(h.state)
                    Text(h.ownerEmail ?: "No owner login", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (h.state == "hold" && !h.holdReason.isNullOrBlank()) Text("Hold reason: ${h.holdReason}", style = MaterialTheme.typography.bodyMedium)

            if (h.approval != "approved") {
                GlassCard(Modifier.fillMaxWidth()) {
                        Text(if (h.approval == "pending") "This hotel is waiting for your approval." else "This application was rejected.", fontWeight = FontWeight.SemiBold)
                        Text("Until it is approved the owner sees a waiting message and guests cannot order.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ dialog = "approve" }) { Text("Approve") }
                            if (h.approval == "pending") OutlinedButton({ dialog = "reject" }) { Text("Reject") }
                        }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Text("HOTEL DETAILS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Info("Phone", h.phone); Info("Address", h.address); Info("GSTIN", h.gstin)
                Info("Applied", dateLabel(h.createdAt)); Info("Approved", dateLabel(h.approvedAt)); Info("Tables", h.tables.toString())
                Info("Orders (30 days)", h.orders30d.toString()); Info("Last order", h.lastOrderAt?.let { ago(it) } ?: "none yet")
                if (!h.adminNote.isNullOrBlank()) Info("Your note", h.adminNote)
            }

            if (h.approval == "approved") {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("SUBSCRIPTION", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Info("Paid through", dateLabel(h.paidThrough)); Info("Trial ends", dateLabel(h.trialEndsAt))
                    Info("Subscription", h.subExpiresAt?.let { "until ${dateLabel(it)}" } ?: "none"); Info("Total paid", h.totalPaid.rupees())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Button({ dialog = "payment" }) { Text("Record payment") }
                        OutlinedButton({ dialog = "extend" }) { Text("Extend") }
                        TextButton({ dialog = "dates" }) { Text("Dates") }
                    }
                }
            }

            Text("Payments from this hotel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (payments.isEmpty()) Text("None recorded yet.", style = MaterialTheme.typography.bodySmall)
            payments.forEach { p -> PaymentRowView(p) { Row { ReceiptButton(p, ui.support); TextButton({ removePayment = p }) { Text("Remove") } } } }

            HorizontalDivider()
            Text("Manage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedButton({ vm.viewHotel(h) }, Modifier.fillMaxWidth()) { Text("View as hotel (read-only)") }
            OutlinedButton(
                { if (!openWhatsApp(context, h.phone, reminderText(h, ui.support))) vm.say("No valid phone number on file for this hotel.") },
                Modifier.fillMaxWidth(), enabled = h.phone != null,
            ) { Text("WhatsApp: payment reminder") }
            OutlinedButton({ dialog = "edit" }, Modifier.fillMaxWidth()) { Text("Edit details and note") }
            if (h.approval == "approved") {
                if (h.onHold) Button({ vm.hold(h, false, null) }, Modifier.fillMaxWidth()) { Text("Release hold") }
                else OutlinedButton({ dialog = "hold" }, Modifier.fillMaxWidth()) { Text("Put on hold") }
            }
            OutlinedButton({ dialog = "reset" }, Modifier.fillMaxWidth(), enabled = h.ownerEmail != null) { Text("Reset owner's password") }
            OutlinedButton(
                { dialog = "delete" }, Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete hotel") }
            Spacer(Modifier.height(24.dp))
        }
    }

    when (dialog) {
        "approve" -> ApproveDialog(h, { dialog = null }) { days -> vm.approve(h, days) }
        "reject" -> ReasonDialog("Reject ${h.name}", "The hotel will see your reason.", "Reject", true, { dialog = null }) { r -> vm.reject(h, r) }
        "hold" -> ReasonDialog("Put ${h.name} on hold", "Ordering stops and the owner sees a hold message. Their data is kept.", "Put on hold", false, { dialog = null }) { r -> vm.hold(h, true, r) }
        "extend" -> ExtendDialog({ dialog = null }) { d -> vm.extend(h, d) }
        "dates" -> DatesDialog(h, { dialog = null }) { t, s -> vm.setDates(h, t, s) }
        "payment" -> PaymentDialog(ui.hotels, h, { dialog = null }) { rid, amount, method, ref, months, date, note -> vm.recordPayment(rid, amount, method, ref, months, date, note) }
        "edit" -> EditHotelDialog(h, { dialog = null }) { n, p, a, g, note -> vm.update(h, n, p, a, g, note) }
        "reset" -> ResetPasswordDialog(h, { dialog = null }) { pw -> vm.resetPassword(h, pw) }
        "delete" -> DeleteHotelDialog(h, { dialog = null }) { typed -> vm.deleteHotel(h, typed) { onBack() } }
    }
    removePayment?.let { p ->
        AlertDialog(
            onDismissRequest = { removePayment = null }, title = { Text("Remove this payment record?") },
            text = { Text("${p.amount.rupees()} by ${p.method.uppercase()} on ${dateLabel(p.paidOn)}. This removes the record only. If the paid-through date should change, use Dates.") },
            confirmButton = { TextButton({ vm.deletePayment(p); removePayment = null }) { Text("Remove") } },
            dismissButton = { TextButton({ removePayment = null }) { Text("Cancel") } },
        )
    }
}
