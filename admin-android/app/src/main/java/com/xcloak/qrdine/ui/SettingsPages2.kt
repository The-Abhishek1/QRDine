package com.xcloak.qrdine.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.Diagnostics
import com.xcloak.qrdine.data.AdminResult
import com.xcloak.qrdine.data.Restaurant
import com.xcloak.qrdine.data.StaffRow
import com.xcloak.qrdine.notify.Prefs
import com.xcloak.qrdine.print.Escpos
import kotlinx.coroutines.launch

private val numKeys = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)

private fun staffMessage(code: String?) = when (code) {
    "bad_email" -> "Enter a valid email address."
    "weak_password" -> "The password needs at least 8 characters."
    "name_required" -> "Enter the person's name."
    "email_taken" -> "That email already has an account."
    "bad_role" -> "Pick waiter or kitchen."
    "too_many_staff" -> "You have reached the limit of staff logins."
    "not_found" -> "That login no longer exists."
    else -> "The action failed. Please try again."
}

/** Owner only: waiter and kitchen logins. */
@Composable
fun StaffPage() {
    val owner = QrDineApp.container.owner
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var staff by remember { mutableStateOf<List<StaffRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showAdd by remember { mutableStateOf(false) }
    var resetFor by remember { mutableStateOf<StaffRow?>(null) }
    var deleteFor by remember { mutableStateOf<StaffRow?>(null) }

    fun reload() { scope.launch { runCatching { owner.staff() }.onSuccess { staff = it }; loading = false } }
    LaunchedEffect(Unit) { reload() }
    fun act(ok: String, block: suspend () -> AdminResult) {
        scope.launch {
            try { val r = block(); if (r.ok) { snackbar.showSnackbar(ok); reload() } else snackbar.showSnackbar(staffMessage(r.error)) }
            catch (e: Exception) { snackbar.showSnackbar("Could not reach the server.") }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Give your team their own logins. A waiter takes orders, serves, opens tables and collects payment. The kitchen only sees what to cook and marks it ready. Neither can change the menu, settings or see insights.", style = MaterialTheme.typography.bodyMedium)
            Button({ showAdd = true }, Modifier.fillMaxWidth()) { Text("Add a staff login") }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!loading && staff.isEmpty()) Text("No staff logins yet.", style = MaterialTheme.typography.bodySmall)
            staff.forEach { m ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name ?: m.email.orEmpty(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("${if (m.role == "kitchen") "Kitchen" else "Waiter"} · ${m.email.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(m.active, { act(if (it) "Login turned on" else "Login turned off") { owner.setStaffActive(m.userId, it) } })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ resetFor = m }) { Text("Reset password") }
                            TextButton({ deleteFor = m }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove") }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var email by remember { mutableStateOf("") }
        var pw by remember { mutableStateOf("") }
        var role by remember { mutableStateOf("waiter") }
        AlertDialog(
            onDismissRequest = { showAdd = false }, title = { Text("New staff login") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose what this login can do", style = MaterialTheme.typography.labelLarge)
                    listOf(
                        "waiter" to ("Waiter" to "Takes orders, manages tables and payments."),
                        "kitchen" to ("Kitchen" to "Sees incoming orders and marks them ready."),
                    ).forEach { (value, option) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { role = value },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = role == value, onClick = { role = value })
                            Column(Modifier.padding(start = 4.dp)) {
                                Text(option.first, fontWeight = FontWeight.SemiBold)
                                Text(option.second, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(email, { email = it.trim() }, label = { Text("Email (their login)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    OutlinedTextField(pw, { pw = it }, label = { Text("Password (8+)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Text("Tell them the email and password. They sign in with the same app.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && email.contains("@") && pw.length >= 8, onClick = { act("Login created") { owner.createStaff(name, email, pw, role) }; showAdd = false }) { Text("Create") }
            },
            dismissButton = { TextButton({ showAdd = false }) { Text("Cancel") } },
        )
    }
    resetFor?.let { m ->
        var pw by remember(m) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { resetFor = null }, title = { Text("New password for ${m.name ?: m.email}") },
            text = { OutlinedTextField(pw, { pw = it }, label = { Text("New password (8+)") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) },
            confirmButton = { TextButton(enabled = pw.length >= 8, onClick = { act("Password reset") { owner.resetStaffPassword(m.userId, pw) }; resetFor = null }) { Text("Reset") } },
            dismissButton = { TextButton({ resetFor = null }) { Text("Cancel") } },
        )
    }
    deleteFor?.let { m ->
        AlertDialog(
            onDismissRequest = { deleteFor = null }, title = { Text("Remove ${m.name ?: m.email}?") },
            text = { Text("Their login is deleted. Orders they handled stay in your records.") },
            confirmButton = { TextButton({ act("Login removed") { owner.deleteStaff(m.userId) }; deleteFor = null }) { Text("Remove") } },
            dismissButton = { TextButton({ deleteFor = null }) { Text("Cancel") } },
        )
    }
}

/** Owner only: the PIN that unlocks manual discounts on a bill. */
@Composable
fun PinPage() {
    val owner = QrDineApp.container.owner
    val scope = rememberCoroutineScope()
    var has by remember { mutableStateOf<Boolean?>(null) }
    var old by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { has = owner.hasManagerPin() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Staff cannot give a manual discount unless they enter this PIN. Share it only with people you trust. Five wrong tries lock discounts for 10 minutes.", style = MaterialTheme.typography.bodyMedium)
        Text(if (has == true) "A PIN is set." else if (has == false) "No PIN set yet, so manual discounts are off." else "…", fontWeight = FontWeight.SemiBold)
        if (has == true) OutlinedTextField(old, { old = it.filter(Char::isDigit).take(6) }, label = { Text("Current PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = numKeys, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(new1, { new1 = it.filter(Char::isDigit).take(6) }, label = { Text("New PIN (4 to 6 digits)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = numKeys, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(new2, { new2 = it.filter(Char::isDigit).take(6) }, label = { Text("Repeat the new PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = numKeys, modifier = Modifier.fillMaxWidth(), isError = new2.isNotEmpty() && new1 != new2)
        msg?.let { Text(it, color = if (it.startsWith("PIN saved")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        Button(
            {
                scope.launch {
                    try {
                        val r = owner.setManagerPin(new1, old.ifBlank { null })
                        msg = if (r.ok) { has = true; old = ""; new1 = ""; new2 = ""; "PIN saved" } else when (r.error) {
                            "old_pin_required" -> "Enter your current PIN."; "old_pin_wrong" -> "The current PIN is wrong."; "pin_locked" -> "Too many wrong tries. Wait 10 minutes."; else -> "Could not save the PIN."
                        }
                    } catch (e: Exception) { msg = if ((e.message ?: "").contains("bad_pin")) "Use 4 to 6 digits." else "Could not save the PIN." }
                }
            },
            enabled = new1.length in 4..6 && new1 == new2 && (has != true || old.length >= 4), modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text("Save PIN") }
    }
}

/** Bluetooth thermal printer for kitchen tickets and bills. */
@SuppressLint("MissingPermission")
@Composable
fun PrinterPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var selectedName by remember { mutableStateOf(Prefs.printerName(context)) }
    var selectedAddress by remember { mutableStateOf(Prefs.printerAddress(context)) }
    var chars by remember { mutableIntStateOf(Prefs.paperChars(context)) }
    var autoKot by remember { mutableStateOf(Prefs.autoKot(context)) }
    var msg by remember { mutableStateOf<String?>(null) }
    val devices = if (granted) runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList()) else emptyList()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Pair your receipt printer in the phone's Bluetooth settings first, then pick it here. Most 58 mm and 80 mm thermal printers work. Print a test page to check yours.", style = MaterialTheme.typography.bodyMedium)
        if (!granted && Build.VERSION.SDK_INT >= 31) Button({ permission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Allow Bluetooth") }
        Text(if (selectedAddress != null) "Selected: ${selectedName ?: selectedAddress}" else "No printer selected", fontWeight = FontWeight.SemiBold)
        devices.forEach { d ->
            val name = runCatching { d.name }.getOrNull() ?: d.address
            Card(Modifier.fillMaxWidth().clickable { Prefs.setPrinter(context, d.address, name); selectedName = name; selectedAddress = d.address }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.Medium); Text(d.address, style = MaterialTheme.typography.bodySmall) }
                    if (d.address == selectedAddress) Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (granted && devices.isEmpty()) Text("No paired Bluetooth devices found.", style = MaterialTheme.typography.bodySmall)
        Text("Paper width", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(chars == 32, { chars = 32; Prefs.setPaperChars(context, 32) }, label = { Text("58 mm") })
            FilterChip(chars == 48, { chars = 48; Prefs.setPaperChars(context, 48) }, label = { Text("80 mm") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Print a kitchen ticket when I accept an order"); Text("Needs a printer to be selected.", style = MaterialTheme.typography.bodySmall) }
            Switch(autoKot, { autoKot = it; Prefs.setAutoKot(context, it) })
        }
        OutlinedButton({
            scope.launch { msg = try { Escpos.send(context, Escpos.testPage(chars)); "Sent a test page." } catch (e: Exception) { "Could not print: ${(e.message ?: "printer error").take(100)}" } }
        }, enabled = selectedAddress != null, modifier = Modifier.fillMaxWidth()) { Text("Print a test page") }
        msg?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text("Bills and tickets print in plain English text (₹ appears as Rs) because most cheap printers have no Indian-script fonts.", style = MaterialTheme.typography.bodySmall)
    }
}

/** Owner only: languages guests can switch to. */
@Composable
fun LanguagesPage(r: Restaurant) {
    val owner = QrDineApp.container.owner
    val scope = rememberCoroutineScope()
    var picked by remember(r) { mutableStateOf(r.languages.toSet()) }
    var msg by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Guests see a language switcher on the menu. Buttons and labels are translated for Hindi and Kannada; for the others they stay in English. Dish and category names show your translation where you typed one (in the dish editor and the category's edit screen), otherwise the English name.", style = MaterialTheme.typography.bodyMedium)
        LANG_NAMES.forEach { (code, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f)); Switch(code in picked, { picked = if (it) picked + code else picked - code })
            }
        }
        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Button({ scope.launch { runCatching { owner.setLanguages(r.id, LANG_NAMES.keys.filter { it in picked }) }.onSuccess { msg = "Saved" }.onFailure { msg = "Could not save" } } }, Modifier.fillMaxWidth().height(50.dp)) { Text("Save") }
        Text("Please have a native speaker check the translated buttons before you rely on them.", style = MaterialTheme.typography.bodySmall)
    }
}

/** Settings that belong to this phone, not the restaurant. */
@Composable
fun DevicePage(onKitchenMode: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kitchen by remember { mutableStateOf(Prefs.kitchenMode(context)) }
    var alertSound by remember { mutableStateOf(Prefs.alertSoundEnabled(context)) }
    var diagnosticsEnabled by remember { mutableStateOf(Diagnostics.isEnabled(context)) }
    var pendingReports by remember { mutableIntStateOf(Diagnostics.pendingCount(context)) }
    var confirmClear by remember { mutableStateOf(false) }
    var diagnosticsMessage by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Kitchen display mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Turn this phone or tablet into the kitchen screen: big text, one tap to mark an order ready, rings for each new order, stays awake.", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(kitchen, { kitchen = it; Prefs.setKitchenMode(context, it); onKitchenMode(it) })
            }
            Text("Sign in with the same owner login on the second device, then switch this on there. Or create a Kitchen login under Staff logins.", style = MaterialTheme.typography.bodySmall)
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Order alert sounds", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (alertSound) "This device rings and vibrates for new orders and kitchen updates."
                        else "Alerts remain visible, but this device will not play or restart alert sounds.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = alertSound,
                    onCheckedChange = { enabled ->
                        Prefs.setAlertSoundEnabled(context, enabled)
                        alertSound = enabled
                    },
                )
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Share diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Optional. When enabled, crash and selected error reports upload after sign-in to help improve the app.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = diagnosticsEnabled,
                    onCheckedChange = { enabled ->
                        Diagnostics.setEnabled(context, enabled)
                        diagnosticsEnabled = enabled
                        pendingReports = Diagnostics.pendingCount(context)
                        diagnosticsMessage = if (enabled) "Diagnostics enabled on this device." else "Diagnostics disabled and queued reports deleted."
                        if (enabled) scope.launch { Diagnostics.flush(context, QrDineApp.container.supabase) }
                    },
                )
            }
            Text(
                "Reports include the error type, app area, app/Android version, device model, and up to 8 stack-frame names. They exclude exception messages, tokens, orders, and review text. Reports are retained for up to 30 days.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (diagnosticsEnabled) "$pendingReports report(s) waiting on this device." else "Diagnostics are off. No reports are collected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            diagnosticsMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            if (pendingReports > 0) {
                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete queued reports") }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete queued reports?") },
            text = { Text("These reports have not uploaded yet. This only removes the copies stored on this device.") },
            confirmButton = {
                TextButton(onClick = {
                    Diagnostics.clearPending(context)
                    pendingReports = 0
                    diagnosticsMessage = "Queued reports deleted."
                    confirmClear = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
