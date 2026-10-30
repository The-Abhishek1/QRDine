package com.xcloak.qrdine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xcloak.qrdine.data.HotelRow
import com.xcloak.qrdine.data.OnlineApplication
import java.time.LocalDate

private val num = KeyboardOptions(keyboardType = KeyboardType.Number)

@Composable
fun ApproveDialog(h: HotelRow, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var days by remember { mutableStateOf("30") }
    val d = days.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Approve ${h.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("They can start taking orders straight away. The free trial starts now.")
                OutlinedTextField(days, { days = it.filter(Char::isDigit).take(3) }, label = { Text("Free trial (days)") }, singleLine = true, keyboardOptions = num)
            }
        },
        confirmButton = { TextButton(enabled = d != null && d in 0..365, onClick = { onConfirm(d!!); onDismiss() }) { Text("Approve") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** Reject / hold: both need a message the hotel will read. */
@Composable
fun ReasonDialog(title: String, hint: String, confirm: String, required: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(hint)
                OutlinedTextField(reason, { reason = it.take(200) }, label = { Text("Message to the hotel") }, minLines = 2)
            }
        },
        confirmButton = { TextButton(enabled = !required || reason.isNotBlank(), onClick = { onConfirm(reason); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ExtendDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var days by remember { mutableStateOf("7") }
    val d = days.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Extend access") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 15, 30).forEach { n -> FilterChip(days == n.toString(), { days = n.toString() }, label = { Text("$n days") }) }
                }
                OutlinedTextField(days, { days = it.filter(Char::isDigit).take(3) }, label = { Text("Days") }, singleLine = true, keyboardOptions = num)
            }
        },
        confirmButton = { TextButton(enabled = d != null && d in 1..365, onClick = { onConfirm(d!!); onDismiss() }) { Text("Extend") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentDialog(hotels: List<HotelRow>, fixed: HotelRow?, onDismiss: () -> Unit, onConfirm: (String, Long, String, String, Int, String, String) -> Unit) {
    val choices = remember(hotels) { hotels.filter { it.approval == "approved" } }
    var hotel by remember { mutableStateOf(fixed ?: choices.firstOrNull()) }
    var open by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("upi") }
    var months by remember { mutableStateOf("1") }
    var ref by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now(IST).toString()) }
    var note by remember { mutableStateOf("") }
    val paise = rupeesToPaise(amount)
    val m = months.toIntOrNull()
    val dateOk = runCatching { LocalDate.parse(date) }.isSuccess
    val valid = hotel != null && paise != null && m != null && m in 1..36 && dateOk

    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Record payment") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fixed != null) Text(fixed.name, style = MaterialTheme.typography.titleMedium)
                else ExposedDropdownMenuBox(open, { open = it }) {
                    OutlinedTextField(hotel?.name ?: "Choose a hotel", {}, readOnly = true, label = { Text("Hotel") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(open, { open = false }) {
                        choices.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { hotel = c; open = false }) }
                    }
                }
                OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(9) }, label = { Text("Amount received (₹)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("cash" to "Cash", "upi" to "UPI", "bank" to "Bank").forEach { (k, l) ->
                        FilterChip(method == k, { method = k; if (k == "cash") ref = "" }, label = { Text(l) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 6, 12).forEach { n -> FilterChip(months == n.toString(), { months = n.toString() }, label = { Text(if (n == 1) "1 month" else "$n months") }) }
                }
                if (method != "cash") OutlinedTextField(ref, { ref = it.take(80) }, label = { Text(if (method == "upi") "UPI reference (optional)" else "UTR / reference (optional)") }, singleLine = true)
                OutlinedTextField(date, { date = it.take(10) }, label = { Text("Paid on (yyyy-mm-dd)") }, singleLine = true, isError = !dateOk)
                OutlinedTextField(note, { note = it.take(300) }, label = { Text("Note (optional)") }, singleLine = true)
                Text("The paid months are added after whatever the hotel already has.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(hotel!!.id, paise!!, method, ref, m!!, date, note); onDismiss() }) { Text("Record") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun OnlineOrderPaymentDialog(
    application: OnlineApplication,
    monthlyFeePaise: Int,
    onDismiss: () -> Unit,
    onConfirm: (months: Int, reference: String, note: String) -> Unit,
) {
    var months by remember { mutableIntStateOf(1) }
    var reference by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record online-order payment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(application.name, style = MaterialTheme.typography.titleMedium)
                Text("Choose the coverage period. Amount: ${(monthlyFeePaise.toLong() * months).rupees()}")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 3, 6, 12).forEach { count ->
                        FilterChip(
                            selected = months == count,
                            onClick = { months = count },
                            label = { Text("$count mo") },
                        )
                    }
                }
                OutlinedTextField(
                    reference,
                    { reference = it.take(100) },
                    label = { Text("Payment reference (optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    note,
                    { note = it.take(300) },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = monthlyFeePaise > 0,
                onClick = { onConfirm(months, reference.trim(), note.trim()); onDismiss() },
            ) { Text("Record payment") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun EditHotelDialog(h: HotelRow, onDismiss: () -> Unit, onConfirm: (String, String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf(h.name) }
    var phone by remember { mutableStateOf(h.phone.orEmpty()) }
    var address by remember { mutableStateOf(h.address.orEmpty()) }
    var gstin by remember { mutableStateOf(h.gstin.orEmpty()) }
    var note by remember { mutableStateOf(h.adminNote.orEmpty()) }
    val gstinOk = gstin.isBlank() || gstin.length == 15
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Edit hotel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(120) }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(phone, { phone = it.take(20) }, label = { Text("Phone") }, singleLine = true)
                OutlinedTextField(address, { address = it.take(200) }, label = { Text("Address") }, minLines = 2)
                OutlinedTextField(gstin, { gstin = it.uppercase().filter(Char::isLetterOrDigit).take(15) }, label = { Text("GSTIN") }, singleLine = true, isError = !gstinOk)
                OutlinedTextField(note, { note = it.take(500) }, label = { Text("Private note (only you see this)") }, minLines = 2)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && gstinOk, onClick = { onConfirm(name, phone, address, gstin, note); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AddHotelDialog(onDismiss: () -> Unit, onConfirm: (String, String, String, String, String, Int) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("30") }
    val d = days.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Add a hotel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Creates the hotel's login and approves it. Share the email and password with the owner; they can change the password in Settings.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it.take(120) }, label = { Text("Hotel name") }, singleLine = true)
                OutlinedTextField(email, { email = it.trim() }, label = { Text("Owner email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(password, { password = it }, label = { Text("Temporary password (8+)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(phone, { phone = it.take(20) }, label = { Text("Phone (optional)") }, singleLine = true)
                OutlinedTextField(address, { address = it.take(200) }, label = { Text("Address (optional)") }, singleLine = true)
                OutlinedTextField(days, { days = it.filter(Char::isDigit).take(3) }, label = { Text("Free trial (days)") }, singleLine = true, keyboardOptions = num)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && email.contains("@") && password.length >= 8 && d != null && d in 0..365,
                onClick = { onConfirm(email, password, name, phone, address, d!!); onDismiss() }) { Text("Add hotel") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ResetPasswordDialog(h: HotelRow, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Reset password for ${h.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sets a new password for ${h.ownerEmail ?: "the owner"}. Tell them the new password; they should change it in Settings.")
                OutlinedTextField(pw, { pw = it }, label = { Text("New password (8+)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = { TextButton(enabled = pw.length >= 8, onClick = { onConfirm(pw); onDismiss() }) { Text("Reset") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DeleteHotelDialog(h: HotelRow, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Delete ${h.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This permanently deletes the hotel, its menu, tables, orders, bills, reviews and the owner's login. Payment records stay in your ledger. This cannot be undone.")
                Text("Consider putting it on hold instead.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(typed, { typed = it }, label = { Text("Type the hotel name to confirm") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = typed.trim() == h.name, onClick = { onConfirm(typed.trim()); onDismiss() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete forever") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DatesDialog(h: HotelRow, onDismiss: () -> Unit, onConfirm: (String, String?) -> Unit) {
    fun d(iso: String?) = iso?.let { runCatching { java.time.OffsetDateTime.parse(it).atZoneSameInstant(IST).toLocalDate().toString() }.getOrNull() }.orEmpty()
    var trial by remember { mutableStateOf(d(h.trialEndsAt)) }
    var sub by remember { mutableStateOf(d(h.subExpiresAt)) }
    val trialOk = runCatching { LocalDate.parse(trial) }.isSuccess
    val subOk = sub.isBlank() || runCatching { LocalDate.parse(sub) }.isSuccess
    fun iso(s: String) = LocalDate.parse(s).atTime(23, 59, 59).atZone(IST).toInstant().toString()
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Adjust dates") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Use this to correct a mistake. Both dates end at 11:59 pm.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(trial, { trial = it.take(10) }, label = { Text("Trial ends (yyyy-mm-dd)") }, singleLine = true, isError = !trialOk)
                OutlinedTextField(sub, { sub = it.take(10) }, label = { Text("Subscription ends (empty = none)") }, singleLine = true, isError = !subOk)
            }
        },
        confirmButton = { TextButton(enabled = trialOk && subOk, onClick = { onConfirm(iso(trial), if (sub.isBlank()) null else iso(sub)); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
