package com.xcloak.qrdine.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import com.xcloak.qrdine.data.HotelView
import com.xcloak.qrdine.data.PaymentRow
import com.xcloak.qrdine.data.SupportInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

// ---------- WhatsApp ----------
fun digitsPhone(phone: String?): String? {
    val d = phone.orEmpty().filter(Char::isDigit)
    return when { d.length == 10 -> "91$d"; d.length in 11..15 -> d; else -> null }
}

/** Opens WhatsApp with a ready message to this number. False when there is no usable number. */
fun openWhatsApp(context: Context, phone: String?, text: String): Boolean {
    val p = digitsPhone(phone) ?: return false
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$p?text=" + Uri.encode(text))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    return true
}

fun daysUntil(iso: String?): Int? = runCatching {
    Math.ceil(Duration.between(Instant.now(), OffsetDateTime.parse(iso).toInstant()).seconds / 86400.0).toInt()
}.getOrNull()

fun reminderText(h: HotelRow, support: SupportInfo): String {
    val what = when (h.state) {
        "expired" -> "your QR Dine subscription ended on ${dateLabel(h.paidThrough)}"
        "grace" -> "your QR Dine payment was due on ${dateLabel(h.paidThrough)} and is now overdue"
        "trial" -> "your QR Dine free trial ends on ${dateLabel(h.paidThrough)}"
        else -> "your QR Dine subscription ends on ${dateLabel(h.paidThrough)}"
    }
    return "Hello ${h.name}, $what. " + (support.paymentInfo?.let { "To renew: $it. " } ?: "Please contact us to renew. ") + "Thank you!"
}

// ---------- dashboard sections ----------
@Composable
fun InsightsSection(ui: PlatformUi, onOpen: (String) -> Unit) {
    val ins = ui.insights ?: return
    Text("Money collected, last 12 months", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    BarColumns(ins.monthly.map { it.amount }, ins.monthly.map { it.month.takeLast(2) }, MaterialTheme.colorScheme.primary)
    val best = ins.monthly.maxByOrNull { it.amount }
    if (best != null && best.amount > 0) Text("Best month: ${best.month} · ${best.amount.rupees()}", style = MaterialTheme.typography.bodySmall)

    val pct = if (ins.approvedTotal > 0) ins.everPaid * 100 / ins.approvedTotal else 0
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Trial to paid: $pct%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${ins.everPaid} of ${ins.approvedTotal} approved hotels have paid at least once. ${ins.trialEndedUnpaid} finished their trial without paying.", style = MaterialTheme.typography.bodySmall)
        }
    }
    @Composable
    fun hotelList(title: String, hint: String, rows: List<com.xcloak.qrdine.data.IdName>) {
        if (rows.isEmpty()) return
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(hint, style = MaterialTheme.typography.bodySmall)
        rows.take(8).forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(r.name + (r.lastOrderAt?.let { " · last order ${ago(it)}" } ?: ""), Modifier.weight(1f))
                TextButton({ onOpen(r.id) }) { Text("Open") }
            }
        }
    }
    hotelList("Not set up yet", "Approved, but no dishes on the menu. A call or WhatsApp usually fixes it.", ins.noMenu)
    hotelList("Menu, but no orders", "They set up a menu and never took an order.", ins.noOrders)
    hotelList("Gone quiet", "No order for 14 days or more.", ins.quiet)
}

@Composable
fun RemindersSection(ui: PlatformUi) {
    val context = LocalContext.current
    val due = ui.hotels.filter { h ->
        h.approval == "approved" && !h.onHold && (h.state == "grace" || h.state == "expired" || ((h.state == "trial" || h.state == "active") && (daysUntil(h.paidThrough) ?: 99) <= 7))
    }
    if (due.isEmpty()) return
    Text("Send a payment reminder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    due.forEach { h ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(h.name, fontWeight = FontWeight.Medium)
                Text(stateStyle(h.state).first + " · " + dateLabel(h.paidThrough), style = MaterialTheme.typography.bodySmall)
            }
            Button({ openWhatsApp(context, h.phone, reminderText(h, ui.support)) }, enabled = digitsPhone(h.phone) != null) { Text("WhatsApp") }
        }
    }
}

// ---------- receipts ----------
@Composable
fun ReceiptButton(p: PaymentRow, support: SupportInfo) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    TextButton({
        scope.launch {
            val file = withContext(Dispatchers.Default) { ReceiptPdf.build(context, support, p) }
            QrSheet.share(context, file, "Share the receipt")
        }
    }) { Text("Receipt") }
}

object ReceiptPdf {
    private const val W = 420
    private const val H = 595

    fun number(p: PaymentRow) = "R-" + (p.receiptNo ?: 0L).toString().padStart(6, '0')

    fun build(context: Context, support: SupportInfo, p: PaymentRow): File {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        val c = page.canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.BLACK }
        var y = 50f
        fun center(t: String, size: Float, bold: Boolean = false) {
            if (t.isBlank()) return
            paint.textSize = size; paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; paint.textAlign = Paint.Align.CENTER
            c.drawText(t, W / 2f, y, paint); y += size + 6
        }
        fun row(a: String, b: String, bold: Boolean = false) {
            paint.textSize = 12f; paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textAlign = Paint.Align.LEFT; c.drawText(a, 40f, y, paint)
            paint.textAlign = Paint.Align.RIGHT; c.drawText(b.take(40), W - 40f, y, paint); y += 20
        }
        center(support.businessName ?: "QR Dine", 18f, true)
        center(support.businessAddress.orEmpty(), 10f)
        center(support.businessGstin?.let { "GSTIN $it" }.orEmpty(), 10f)
        y += 10
        center("PAYMENT RECEIPT", 14f, true)
        y += 6
        row("Receipt no", number(p), true)
        row("Date", dateLabel(p.paidOn))
        row("Received from", p.hotelName)
        row("Amount", p.amount.rupees(), true)
        row("Paid by", p.method.uppercase())
        p.reference?.let { row("Reference", it) }
        row("Covers", if (p.validFrom != null && p.validUntil != null) "${dateLabel(p.validFrom)} to ${dateLabel(p.validUntil)}" else "${p.months} month(s)")
        p.note?.let { row("Note", it) }
        y += 20
        center("Thank you for your payment.", 12f)
        center("This is a payment receipt for your QR Dine subscription.", 9f)
        doc.finishPage(page)
        val file = File(File(context.cacheDir, "exports").apply { mkdirs() }, "receipt-${number(p)}.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }
}

// ---------- read-only look at a hotel ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewHotelScreen(v: HotelView, name: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$name (read-only)", maxLines = 1, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
                Text("A support view. You cannot change anything here, and this look was recorded in your activity log.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
            StateChip(v.hotel.state.ifBlank { "trial" })
            Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                listOf(
                    "Open for orders: ${if (v.hotel.isOpen) "yes" else "no"}", "Tables: ${if (v.hotel.mode == "open") "open ordering" else "staff opens them"}",
                    "GST: ${v.hotel.gstMode}${if (v.hotel.gstMode == "regular") " ${v.hotel.gstRate}%" else ""}", "UPI: ${v.hotel.upiId ?: "not set"}",
                    "Languages: ${if (v.hotel.languages.isEmpty()) "English only" else v.hotel.languages.joinToString()}",
                    "Menu: ${v.menu.dishes} dishes in ${v.menu.categories} categories, ${v.menu.unavailable} sold out",
                ).joinToString("\n"), style = MaterialTheme.typography.bodyMedium,
            )
            Text("Today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${v.today.revenue.rupees()} from ${v.today.bills} bills · ${v.today.items} dishes · cash ${v.today.cash.rupees()} · UPI ${v.today.upi.rupees()}")
            Text("Tables in service (${v.serving.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (v.serving.isEmpty()) Text("None right now.", style = MaterialTheme.typography.bodySmall)
            v.serving.forEach { Text("${it.table}: ${it.total.rupees()} · ${it.cooking} cooking · ${it.served} served" + if (it.status == "bill_requested") " · bill requested" else "") }
            Text("Staff", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            v.staff.forEach { Text("${it.name ?: "?"} · ${it.role}${if (!it.active) " (off)" else ""}") }
            Text("Latest orders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            v.recentOrders.forEach { Text("${it.table} · ${it.status} · ${it.total.rupees()} · ${ago(it.createdAt)}", style = MaterialTheme.typography.bodyMedium) }
            Text("Latest bills", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            v.recentBills.forEach { Text("${it.fy}/${it.number.toString().padStart(4, '0')} · table ${it.table} · ${it.total.rupees()} · ${istTime(it.paidAt, "dd MMM, hh:mm a")}", style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
