package com.xcloak.qrdine.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.CouponRow
import com.xcloak.qrdine.data.HotelStatus
import com.xcloak.qrdine.data.Restaurant
import com.xcloak.qrdine.notify.AlertHealth
import com.xcloak.qrdine.notify.AlertSound
import com.xcloak.qrdine.notify.Notifier
import kotlinx.coroutines.delay

private val TITLES = mapOf(
    "home" to "Settings", "profile" to "Restaurant details", "billing" to "Billing, tax and UPI", "ordering" to "Ordering mode",
    "online" to "Online orders",
    "offers" to "Offers and announcement", "alerts" to "Alert health", "account" to "Account",
    "staff" to "Staff logins", "pin" to "Manager PIN", "printer" to "Receipt printer", "languages" to "Guest languages", "device" to "This device",
)
private val UPI_ID_PATTERN = Regex("[A-Za-z0-9._-]{2,64}@[A-Za-z0-9.-]{2,64}")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(restaurantId: String, bottomBar: @Composable () -> Unit, status: HotelStatus? = null, role: String = "owner", onKitchenMode: (Boolean) -> Unit = {}) {
    val vm: SettingsViewModel = viewModel(
        key = "settings-$restaurantId",
        factory = SettingsViewModel.Factory(restaurantId),
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val auth: AuthViewModel = viewModel()
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf("home") }
    val snackbar = remember { SnackbarHostState() }
    val noBar: @Composable () -> Unit = {}
    BackHandler(enabled = page != "home") { page = "home" }
    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); vm.messageShown() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = if (page == "home") bottomBar else noBar,
        topBar = {
            TopAppBar(
                title = { Text(TITLES[page] ?: "Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { if (page != "home") IconButton({ page = "home" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val r = s.restaurant
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                s.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                r == null -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Could not load your restaurant. Pull to retry from the Orders tab.", color = MaterialTheme.colorScheme.error)
                    Text("Signed in as ${s.email ?: "Unknown account"}", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { auth.signOut() }) { Text("Sign out") }
                }
                page == "home" -> Home(r, status, role, onOpen = {
                    when (it) {
                        "privacy", "terms" -> openLegalPage(context, it)
                        else -> page = it
                    }
                })
                page == "profile" -> ProfilePage(r, s.busy, vm)
                page == "billing" -> BillingPage(r, s.busy, vm)
                page == "ordering" -> OrderingPage(r, s.busy, vm)
                page == "online" -> OnlineOrdersPage(r, s.onlineCodAvailable, s.busy, vm)
                page == "offers" -> OffersPage(s.announcement, s.coupons, s.busy, vm)
                page == "alerts" -> AlertsPage(r.isOpen)
                page == "staff" -> StaffPage()
                page == "pin" -> PinPage()
                page == "printer" -> PrinterPage()
                page == "languages" -> LanguagesPage(r)
                page == "device" -> DevicePage(onKitchenMode)
                else -> AccountPage(s.email, s.busy, vm)
            }
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun Home(r: Restaurant, status: HotelStatus?, role: String, onOpen: (String) -> Unit) {
    val sub = when (status?.state) {
        "trial" -> "Free trial: ${status.daysLeft ?: 0} day(s) left"
        "active" -> "Subscription active until ${dateLabel(status.paidThrough)}"
        "grace" -> "Payment overdue. Please renew to keep taking orders."
        "expired" -> "Subscription ended. Guests cannot order."
        else -> ""
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            Text("RESTAURANT SETTINGS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (r.logoUrl != null) {
                    AsyncImage(r.logoUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)))
                } else {
                    Surface(
                        Modifier.size(64.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text(r.name.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(r.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(sub.ifBlank { "Manage how your restaurant runs." }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        ThemePreferenceCard()
        if (status != null && (status.state == "grace" || status.state == "expired" || (status.daysLeft ?: 99) <= 7)) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Renewing", fontWeight = FontWeight.Bold)
                    Text(listOfNotNull(status.support.phone?.let { "Call $it" }, status.support.email).joinToString("  ·  ").ifBlank { "Contact your account manager." })
                    status.support.paymentInfo?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        val sections = buildList<Pair<String, List<Triple<String, String, String>>>> {
            if (role == "owner") {
                add(
                    "RESTAURANT" to listOf(
                        Triple("profile", "Restaurant details", "Name, photo, address, phone, FSSAI"),
                        Triple("billing", "Billing, tax and UPI", "GST, service charge, UPI ID for guests"),
                        Triple("ordering", "Ordering mode", if (r.mode == "open") "Tables open automatically on scan" else "You open each table"),
                        Triple("online", "Online orders", if (r.onlineOrdersEnabled) "Accept delivery and pickup orders" else "Set up customer app orders"),
                        Triple("offers", "Offers and announcement", "Today's message to guests, coupon codes"),
                        Triple("languages", "Guest languages", if (r.languages.isEmpty()) "English only" else "English + " + r.languages.joinToString { LANG_NAMES[it] ?: it }),
                        Triple("staff", "Staff logins", "Waiter and kitchen accounts"),
                        Triple("pin", "Manager PIN", "Needed to give a discount on a bill"),
                    ),
                )
            }
            add(
                "SERVICE & DEVICES" to listOf(
                    Triple("printer", "Receipt printer", "Bluetooth thermal printer, kitchen tickets"),
                    Triple("device", "This device", "Kitchen display mode"),
                    Triple("alerts", "Alert health", "Check why alerts might be silent, send a test"),
                ),
            )
            add("ACCOUNT" to listOf(Triple("account", "Account", "Email, password, sign out")))
            add("LEGAL" to listOf(
                Triple("privacy", "Privacy policy", "How QR Dine handles information"),
                Triple("terms", "Terms of use", "Rules for using QR Dine"),
            ))
        }
        sections.forEach { (heading, items) ->
            Text(heading, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            items.forEach { (key, title, subtitle) ->
                GlassCard(Modifier.fillMaxWidth().clickable { onOpen(key) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Open $title",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfilePage(r: Restaurant, busy: Boolean, vm: SettingsViewModel) {
    val context = LocalContext.current
    var name by remember(r) { mutableStateOf(r.name) }
    var address by remember(r) { mutableStateOf(r.address.orEmpty()) }
    var phone by remember(r) { mutableStateOf(r.phone.orEmpty()) }
    var fssai by remember(r) { mutableStateOf(r.fssai.orEmpty()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.uploadLogo(context, uri) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (r.logoUrl != null) AsyncImage(r.logoUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.size(80.dp).clip(RoundedCornerShape(16.dp)))
            else Surface(Modifier.size(80.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Logo") } }
            OutlinedButton({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy) { Text(if (r.logoUrl != null) "Change photo" else "Add photo") }
        }
        Text("The photo and name show at the top of the guest menu and on bills.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(name, { name = it.take(120) }, label = { Text("Restaurant name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(address, { address = it.take(200) }, label = { Text("Address (printed on bills)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(phone, { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(20) }, label = { Text("Phone") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(fssai, { fssai = it.filter(Char::isLetterOrDigit).take(20) }, label = { Text("FSSAI licence number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button({ vm.saveProfile(name, address, phone, fssai) }, enabled = !busy && name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Save") }
    }
}

@Composable
private fun BillingPage(r: Restaurant, busy: Boolean, vm: SettingsViewModel) {
    var mode by remember(r) { mutableStateOf(r.gstMode) }
    var rate by remember(r) { mutableStateOf(r.gstRate.toString().removeSuffix(".0")) }
    var gstin by remember(r) { mutableStateOf(r.gstin.orEmpty()) }
    var service by remember(r) { mutableStateOf(r.serviceChargePct.toString().removeSuffix(".0")) }
    var upi by remember(r) { mutableStateOf(r.upiId.orEmpty()) }

    val rateVal = rate.toDoubleOrNull()
    val serviceVal = service.toDoubleOrNull()
    val error = when {
        mode == "regular" && (rateVal == null || rateVal < 0 || rateVal > 28) -> "GST rate must be between 0 and 28."
        serviceVal == null || serviceVal < 0 || serviceVal > 20 -> "Service charge must be between 0 and 20."
        gstin.isNotBlank() && gstin.length != 15 -> "A GSTIN has exactly 15 characters."
        upi.isNotBlank() && !UPI_ID_PATTERN.matches(upi.trim()) -> "Enter a valid UPI ID, such as name@bank."
        else -> null
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("GST on bills", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("none" to "No GST", "regular" to "Regular GST", "composition" to "Composition").forEach { (k, label) -> FilterChip(mode == k, { mode = k }, label = { Text(label) }) }
        }
        Text(
            when (mode) {
                "regular" -> "GST is added to every bill and shown as CGST and SGST, and bills are titled TAX INVOICE."
                "composition" -> "Composition dealers cannot collect GST from guests, so no tax is added. Bills are plain bills."
                else -> "No tax is added to bills."
            }, style = MaterialTheme.typography.bodySmall,
        )
        if (mode == "regular") OutlinedTextField(rate, { rate = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("GST rate (%)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(gstin, { gstin = it.uppercase().filter(Char::isLetterOrDigit).take(15) }, label = { Text("GSTIN (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(service, { service = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("Service charge (%)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Leave 0 if you do not charge one. A service charge must be voluntary: guests may ask you to remove it.") })
        HorizontalDivider()
        Text("UPI for guests", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(upi, { upi = it.trim().take(60) }, label = { Text("UPI ID (e.g. hotel@okbank)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Guests see a Pay with UPI button after requesting the bill. You still confirm the credit in your own UPI app.") })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button({ vm.saveBilling(mode, rateVal ?: 5.0, gstin, serviceVal ?: 0.0, upi.trim()) }, enabled = !busy && error == null, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Save") }
        Text("Have your CA confirm the GST settings before you print bills.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OrderingPage(r: Restaurant, busy: Boolean, vm: SettingsViewModel) {
    val open = r.mode == "open"
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Open ordering", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (open) "On: a guest who scans a closed table opens it automatically." else "Off: you tap Open table when guests sit down.", style = MaterialTheme.typography.bodyMedium)
            }
            Switch(open, { vm.setMode(if (it) "open" else "staff_activated") }, enabled = !busy)
        }
        Text("Staff-opened tables are safer: nobody can order from a photo of the QR code. Use open ordering only if you can watch every table. Every order still needs your approval either way.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OnlineOrdersPage(r: Restaurant, codAvailable: Boolean, busy: Boolean, vm: SettingsViewModel) {
    var enabled by remember(r) { mutableStateOf(r.onlineOrdersEnabled) }
    var delivery by remember(r) { mutableStateOf(r.onlineDeliveryEnabled) }
    var pickup by remember(r) { mutableStateOf(r.onlinePickupEnabled) }
    var upi by remember(r) { mutableStateOf(r.onlineUpiEnabled) }
    var cod by remember(r, codAvailable) { mutableStateOf(r.onlineCodEnabled && codAvailable) }
    var deliveryFee by remember(r) { mutableStateOf("%.2f".format(java.util.Locale.US, r.onlineDeliveryFee / 100.0)) }
    var codFee by remember(r) { mutableStateOf("%.2f".format(java.util.Locale.US, r.onlineCodFee / 100.0)) }
    var applicationNote by remember(r) { mutableStateOf(r.onlineApplicationNote.orEmpty()) }

    fun parseFee(value: String): Int? = runCatching {
        val amount = java.math.BigDecimal(value.trim())
        if (amount.signum() < 0 || amount.scale() > 2 || amount > java.math.BigDecimal("1000")) null
        else amount.movePointRight(2).intValueExact()
    }.getOrNull()
    val deliveryFeePaise = parseFee(deliveryFee)
    val codFeePaise = parseFee(codFee)
    val feeError = when {
        delivery && deliveryFeePaise == null -> "Delivery fee must be between ₹0 and ₹1,000, with at most two decimal places."
        cod && codFeePaise == null -> "COD fee must be between ₹0 and ₹1,000, with at most two decimal places."
        else -> null
    }
    val setupError = when {
        enabled && !delivery && !pickup -> "Choose delivery, pickup, or both before enabling online orders."
        enabled && !upi && !cod -> "Choose at least one payment method before enabling online orders."
        upi && !UPI_ID_PATTERN.matches(r.upiId.orEmpty().trim()) -> "Add a valid UPI ID under Billing, tax and UPI before enabling UPI payments."
        else -> null
    }
    val error = setupError ?: feeError

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Customer app ordering", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "This controls orders placed through QR Dine's customer app. It does not change table QR ordering.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val appStatus = when (r.onlineApplicationStatus) {
            "pending" -> "Application submitted — waiting for platform-admin approval."
            "approved" -> "Online-order application approved. Configure your services and enable orders when ready."
            "rejected" -> "Application not approved: ${r.onlineApplicationReview ?: "Update your details and apply again."}"
            else -> "Submit an application. Online ordering only becomes available after platform-admin approval."
        }
        Text(appStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        if (r.onlineApplicationStatus != "approved" && r.onlineApplicationStatus != "pending") {
            OutlinedTextField(
                applicationNote,
                { applicationNote = it.take(500) },
                label = { Text("Delivery / pickup service details (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.submitOnlineApplication(applicationNote) },
                enabled = !busy && r.phone?.isNotBlank() == true && r.address?.isNotBlank() == true && (delivery || pickup),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Submit online-order application") }
            if (r.phone.isNullOrBlank() || r.address.isNullOrBlank() || (!delivery && !pickup)) {
                Text("Complete your restaurant phone, address, and delivery or pickup settings before applying.", style = MaterialTheme.typography.bodySmall)
            }
        }
        SettingSwitch(
            title = "Accept online orders",
            detail = if (r.onlineApplicationStatus == "approved") "Pause or resume the online ordering listing." else "Available after your application is approved.",
            checked = enabled,
            onCheckedChange = { enabled = it },
            enabled = r.onlineApplicationStatus == "approved",
        )
        HorizontalDivider()
        Text("Fulfilment", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        SettingSwitch("Delivery", "Customers order for delivery from your restaurant.", delivery, { delivery = it })
        if (delivery) {
            OutlinedTextField(
                value = deliveryFee,
                onValueChange = { deliveryFee = it.filter { c -> c.isDigit() || c == '.' }.take(7) },
                label = { Text("Delivery fee (₹)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SettingSwitch("Pickup", "Customers collect their order from your restaurant.", pickup, { pickup = it })
        HorizontalDivider()
        Text("Payment methods", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        SettingSwitch("UPI", "Customers pay using your restaurant UPI ID.", upi, { upi = it })
        if (upi && r.upiId.isNullOrBlank()) {
            Text("Add your UPI ID under Billing, tax and UPI first.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        SettingSwitch(
            "Cash on delivery",
            if (codAvailable) "Customer pays your staff when the order arrives."
            else "UPI-only launch period. The platform admin will open COD later.",
            cod,
            { cod = it },
            enabled = codAvailable,
        )
        if (cod) {
            OutlinedTextField(
                value = codFee,
                onValueChange = { codFee = it.filter { c -> c.isDigit() || c == '.' }.take(7) },
                label = { Text("COD fee (₹)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "Fees are shown to customers before they confirm an order. Rain or temporary surcharges are not configured here.",
            style = MaterialTheme.typography.bodySmall,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = {
                vm.saveOnlineOrderSettings(
                    enabled,
                    delivery,
                    pickup,
                    upi,
                    cod,
                    deliveryFeePaise ?: r.onlineDeliveryFee,
                    codFeePaise ?: r.onlineCodFee,
                )
            },
            enabled = !busy && error == null,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text("Save online order settings") }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun OffersPage(announcement: String, coupons: List<CouponRow>, busy: Boolean, vm: SettingsViewModel) {
    var text by remember(announcement) { mutableStateOf(announcement) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CouponRow?>(null) }
    var deleting by remember { mutableStateOf<CouponRow?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Today's announcement", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(text, { text = it.take(300) }, label = { Text("Shown at the top of the menu today") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("For example: Kitchen closes at 10 pm. Special thali today.") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.saveAnnouncement(text) }, enabled = !busy && text != announcement) { Text("Save") }
            if (announcement.isNotBlank()) OutlinedButton({ vm.saveAnnouncement("") }, enabled = !busy) { Text("Remove") }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Coupons", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button({ showAdd = true }, enabled = !busy) { Text("Add coupon") }
        }
        Text("Guests tell you the code and you apply it when settling the bill.", style = MaterialTheme.typography.bodySmall)
        if (coupons.isEmpty()) Text("No coupons yet.")
        coupons.forEach { c ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.code, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(
                                (if (c.type == "percent") "${c.value}% off" else "${c.value.toLong().rupees()} off") +
                                    (if (c.minBill > 0) " · min bill ${c.minBill.toLong().rupees()}" else "") +
                                    " · used ${c.usedCount}" + (c.maxUses?.let { " of $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (!c.active) Text("Inactive", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                        Switch(c.active, { vm.setCouponActive(c, it) }, enabled = !busy)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editing = c }, enabled = !busy) { Text("Edit") }
                        TextButton(onClick = { deleting = c }, enabled = !busy) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    if (showAdd || editing != null) {
        CouponEditorDialog(
            coupon = editing,
            onDismiss = { showAdd = false; editing = null },
            onSave = { code, type, value, minBill, maxUses ->
                val amount = if (type == "percent") value else value * 100
                val minimum = minBill * 100
                if (editing == null) vm.addCoupon(code, type, amount, minimum, maxUses)
                else vm.updateCoupon(editing!!, code, type, amount, minimum, maxUses)
                showAdd = false
                editing = null
            },
        )
    }
    deleting?.let { coupon ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${coupon.code}?") },
            text = {
                Text(
                    if (coupon.usedCount > 0) {
                        "This coupon has been used. It will be deactivated and kept for bill history."
                    } else {
                        "This coupon has not been used and will be permanently deleted."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteCoupon(coupon); deleting = null }) {
                    Text(if (coupon.usedCount > 0) "Deactivate" else "Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CouponEditorDialog(coupon: CouponRow?, onDismiss: () -> Unit, onSave: (String, String, Int, Int, Int?) -> Unit) {
    var code by remember(coupon?.id) { mutableStateOf(coupon?.code.orEmpty()) }
    var type by remember(coupon?.id) { mutableStateOf(coupon?.type ?: "percent") }
    var value by remember(coupon?.id) {
        mutableStateOf(
            when {
                coupon == null -> ""
                coupon.type == "percent" -> coupon.value.toString()
                else -> (coupon.value / 100).toString()
            },
        )
    }
    var minBill by remember(coupon?.id) { mutableStateOf(coupon?.minBill?.div(100)?.takeIf { it > 0 }?.toString().orEmpty()) }
    var maxUses by remember(coupon?.id) { mutableStateOf(coupon?.maxUses?.toString().orEmpty()) }
    val amount = value.toIntOrNull()
    val valid = code.length >= 3 && amount != null && amount > 0 && (type != "percent" || amount <= 100)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (coupon == null) "New coupon" else "Edit coupon") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(code, { code = it.uppercase().filter(Char::isLetterOrDigit).take(20) }, label = { Text("Code (e.g. SAVE10)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(type == "percent", { type = "percent" }, label = { Text("Percent off") })
                    FilterChip(type == "flat", { type = "flat" }, label = { Text("₹ off") })
                }
                OutlinedTextField(value, { value = it.filter(Char::isDigit).take(6) }, label = { Text(if (type == "percent") "Percent (1 to 100)" else "Amount in ₹") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(minBill, { minBill = it.filter(Char::isDigit).take(6) }, label = { Text("Minimum bill in ₹ (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(maxUses, { maxUses = it.filter(Char::isDigit).take(5) }, label = { Text("Max uses (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(code, type, amount!!, minBill.toIntOrNull() ?: 0, maxUses.toIntOrNull()) },
            ) { Text(if (coupon == null) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AlertsPage(open: Boolean) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(AlertHealth.check(context, open)) }
    LaunchedEffect(open) { while (true) { items = AlertHealth.check(context, open); delay(2000) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("If an order, bill request or waiter call ever arrives without a sound, one of these is usually why.", style = MaterialTheme.typography.bodyMedium)
        items.forEach {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text((if (it.ok) "✓  " else "✗  ") + it.title, fontWeight = FontWeight.SemiBold,
                        color = if (it.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    if (!it.ok) Text(it.fix, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Button({
            Notifier.alert(context, "Test alert", "If you hear this, alerts work.")
            AlertSound.play(context)
        }, Modifier.fillMaxWidth().height(50.dp)) { Text("Send a test alert") }
        OutlinedButton({ AlertSound.stop(); Notifier.clearAlert(context) }, Modifier.fillMaxWidth()) { Text("Stop the sound") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, Modifier.weight(1f)) { Text("Notification settings") }
            OutlinedButton({
                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, Modifier.weight(1f)) { Text("Battery settings") }
        }
        Text("On Xiaomi, Oppo, Vivo, Realme and OnePlus phones also allow Autostart and lock the app in the recent apps list.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AccountPage(email: String?, busy: Boolean, vm: SettingsViewModel) {
    val auth: AuthViewModel = viewModel()
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var confirmOut by remember { mutableStateOf(false) }
    val error = when {
        pw.isNotEmpty() && pw.length < 8 -> "Use at least 8 characters."
        pw2.isNotEmpty() && pw != pw2 -> "The passwords do not match."
        else -> null
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Signed in as", style = MaterialTheme.typography.labelMedium)
        Text(email ?: "–", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        HorizontalDivider()
        Text("Change password", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(pw, { pw = it }, label = { Text("New password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pw2, { pw2 = it }, label = { Text("Repeat new password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button({ vm.changePassword(pw); pw = ""; pw2 = "" }, enabled = !busy && pw.length >= 8 && pw == pw2, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Change password") }
        HorizontalDivider()
        OutlinedButton({ confirmOut = true }, Modifier.fillMaxWidth()) { Text("Sign out") }
        Text("Signing out stops order alerts on this phone.", style = MaterialTheme.typography.bodySmall)
    }
    if (confirmOut) {
        AlertDialog(
            onDismissRequest = { confirmOut = false },
            title = { Text("Sign out?") },
            text = { Text("This phone will stop ringing for new orders until you sign in again.") },
            confirmButton = { TextButton({ confirmOut = false; auth.signOut() }) { Text("Sign out") } },
            dismissButton = { TextButton({ confirmOut = false }) { Text("Cancel") } },
        )
    }
}
