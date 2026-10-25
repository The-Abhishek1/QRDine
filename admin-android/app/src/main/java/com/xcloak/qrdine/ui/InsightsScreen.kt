package com.xcloak.qrdine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.InvoiceRow
import com.xcloak.qrdine.data.ReviewRow
import com.xcloak.qrdine.data.Stats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

private enum class Range(val label: String) { TODAY("Today"), WEEK("7 days"), MONTH30("30 days"), THIS_MONTH("This month"), CUSTOM("Custom") }

private data class Insights(
    val stats: Stats, val invoices: List<InvoiceRow>, val reviews: List<ReviewRow>, val days: List<LocalDate>,
    val restaurantName: String, val start: LocalDate, val end: LocalDate,
)

private fun startOf(r: Range, today: LocalDate, custom: LocalDate): LocalDate = when (r) {
    Range.CUSTOM -> custom
    Range.TODAY -> today
    Range.WEEK -> today.minusDays(6)
    Range.MONTH30 -> today.minusDays(29)
    Range.THIS_MONTH -> today.withDayOfMonth(1)
}

private fun csvCell(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

private fun buildCsv(rows: List<InvoiceRow>): String {
    val sb = StringBuilder("Bill no,Date,Table,Payment,Items,Discount,Service charge,CGST,SGST,Round off,Total\n")
    fun rs(p: Long) = "%.2f".format(java.util.Locale.US, p / 100.0)
    for (r in rows.sortedBy { it.issuedAt }) {
        val b = r.snapshot.bill
        sb.append(listOf(
            csvCell(InvoicePdf.fileLabel(r)), csvCell(istTime(r.snapshot.paidAt, "yyyy-MM-dd HH:mm")), csvCell(r.snapshot.table),
            csvCell(r.snapshot.method), rs(b.subtotal), rs(b.discount), rs(b.serviceCharge), rs(b.cgst), rs(b.sgst), rs(b.roundOff), rs(b.total),
        ).joinToString(",")).append('\n')
    }
    return sb.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(bottomBar: @Composable () -> Unit, onHistory: () -> Unit = {}, onCashCount: () -> Unit = {}) {
    val context = LocalContext.current
    val owner = QrDineApp.container.owner
    val scope = rememberCoroutineScope()
    var range by rememberSaveable { mutableStateOf(Range.WEEK) }
    var data by remember { mutableStateOf<Insights?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var lowestFirst by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var customStart by rememberSaveable { mutableLongStateOf(LocalDate.now(IST).minusDays(6).toEpochDay()) }
    var customEnd by rememberSaveable { mutableLongStateOf(LocalDate.now(IST).toEpochDay()) }
    var showPicker by remember { mutableStateOf(false) }
    var noteFor by remember { mutableStateOf<ReviewRow?>(null) }

    LaunchedEffect(range, reload, customStart, customEnd) {
        loading = true
        try {
            val today = LocalDate.now(IST)
            val start = startOf(range, today, LocalDate.ofEpochDay(customStart))
            val end = if (range == Range.CUSTOM) minOf(LocalDate.ofEpochDay(customEnd), today) else today
            val from = start.atStartOfDay(IST).toInstant().toString()
            val to = end.plusDays(1).atStartOfDay(IST).toInstant().toString()
            val stats = owner.stats(from, to)
            val invoices = owner.invoices(from, to)
            val reviews = runCatching { owner.reviews() }.getOrDefault(emptyList())
            val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
            val name = runCatching { owner.restaurant().name }.getOrDefault("")
            data = Insights(stats, invoices, reviews, days, name, start, end)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = (e.message ?: "Could not load insights").take(160)
        } finally { loading = false }
    }

    Scaffold(bottomBar = bottomBar, topBar = { TopAppBar(title = { Text("Insights", fontWeight = FontWeight.SemiBold) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScreenIntro("BUSINESS OVERVIEW", "Performance at a glance", "Track sales, popular dishes, and guest feedback.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Range.entries.forEach { r ->
                    FilterChip(
                        range == r, { if (r == Range.CUSTOM) showPicker = true else range = r },
                        label = { Text(if (r == Range.CUSTOM && range == Range.CUSTOM) "${LocalDate.ofEpochDay(customStart).dayOfMonth}/${LocalDate.ofEpochDay(customStart).monthValue} to ${LocalDate.ofEpochDay(customEnd).dayOfMonth}/${LocalDate.ofEpochDay(customEnd).monthValue}" else r.label) },
                    )
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val d = data
            if (d != null) {
                val st = d.stats
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("SALES REPORT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("${d.start.dayOfMonth} ${d.start.month.name.lowercase().replaceFirstChar(Char::uppercase)} – ${d.end.dayOfMonth} ${d.end.month.name.lowercase().replaceFirstChar(Char::uppercase)}")
                    Button({
                        scope.launch {
                            val file = withContext(Dispatchers.Default) { SalesReportPdf.build(context, d.restaurantName, d.start, d.end, st, d.invoices.size) }
                            QrSheet.share(context, file, "Share the sales summary")
                        }
                    }, Modifier.fillMaxWidth().height(50.dp)) { Text("Download sales summary · PDF") }
                }
                if (st.bills == 0) Text("No paid bills in this period yet. Charts appear after the first bill is marked paid.", style = MaterialTheme.typography.bodyMedium)

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("Revenue", st.revenue.rupees(), Modifier.weight(1f))
                    Kpi("Bills", st.bills.toString(), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("Average bill", if (st.bills > 0) (st.revenue / st.bills).rupees() else "–", Modifier.weight(1f))
                    Kpi("Dishes sold", st.items.toString(), Modifier.weight(1f))
                }
                if (st.bills > 0) Text("Revenue includes ${st.tax.rupees()} GST (${(st.revenue - st.tax).rupees()} without it). Discounts given: ${st.discounts.rupees()}.", style = MaterialTheme.typography.bodySmall)

                if (d.days.size > 1) {
                    Section("Revenue per day") {
                        val byDay = st.daily.associate { it.day to it.revenue }
                        val values = d.days.map { byDay[it.toString()] ?: 0L }
                        val step = (d.days.size + 7) / 8
                        VBars(values, d.days.mapIndexed { i, day -> if (i % step == 0) day.dayOfMonth.toString() else "" }, MaterialTheme.colorScheme.primary)
                        Text("Best day: ${values.maxOrNull()?.rupees() ?: "–"}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (st.cash + st.upi > 0) Section("Cash vs UPI") {
                    val total = (st.cash + st.upi).toFloat()
                    Row(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(6.dp))) {
                        if (st.cash > 0) Box(Modifier.weight(st.cash / total).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                        if (st.upi > 0) Box(Modifier.weight(st.upi / total).fillMaxHeight().background(Color(0xFFF2B632)))
                    }
                    Text("Cash ${st.cash.rupees()}   ·   UPI ${st.upi.rupees()}", style = MaterialTheme.typography.bodyMedium)
                }

                if (st.dishes.isNotEmpty()) {
                    Section("Top dishes") {
                        val max = st.dishes.maxOf { it.qty }.toFloat()
                        st.dishes.take(5).forEach { HBar("${it.name}", it.qty / max, "${it.qty}", MaterialTheme.colorScheme.primary) }
                    }
                    if (st.dishes.size > 5) Section("Slowest sellers") {
                        val max = st.dishes.maxOf { it.qty }.toFloat()
                        st.dishes.sortedBy { it.qty }.take(5).forEach { HBar(it.name, it.qty / max, "${it.qty}", Color(0xFFC2371E)) }
                        Text("Consider a special on these, or remove them from the menu.", style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (st.hours.isNotEmpty()) Section("Busy hours (orders)") {
                    val byHour = st.hours.associate { it.hour to it.orders.toLong() }
                    VBars((0..23).map { byHour[it] ?: 0L }, (0..23).map { if (it % 3 == 0) it.toString() else "" }, Color(0xFFF2B632))
                    val peak = st.hours.maxByOrNull { it.orders }
                    if (peak != null) Text("Busiest: ${peak.hour}:00 to ${peak.hour + 1}:00 (${peak.orders} orders)", style = MaterialTheme.typography.bodySmall)
                }

                Section("Reviews") {
                    val food = d.reviews.mapNotNull { it.ratingFood }
                    val service = d.reviews.mapNotNull { it.ratingService }
                    if (d.reviews.isEmpty()) Text("No reviews yet. Guests can rate their visit after you mark the bill paid.")
                    else {
                        Text("Food ${if (food.isEmpty()) "–" else "%.1f".format(java.util.Locale.US, food.average())}  ·  Service ${if (service.isEmpty()) "–" else "%.1f".format(java.util.Locale.US, service.average())}  ·  ${d.reviews.size} reviews", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!lowestFirst, { lowestFirst = false }, label = { Text("Newest") })
                            FilterChip(lowestFirst, { lowestFirst = true }, label = { Text("Lowest first") })
                        }
                        val sorted = if (lowestFirst) d.reviews.sortedBy { ((it.ratingFood ?: 5) + (it.ratingService ?: 5)) } else d.reviews
                        sorted.take(8).forEach { r ->
                            val low = minOf(r.ratingFood ?: 5, r.ratingService ?: 5) <= 2
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(
                                    "Food ${stars(r.ratingFood)}   Service ${stars(r.ratingService)}" + if (low) "   (unhappy)" else "",
                                    fontWeight = FontWeight.Medium, color = if (low) MaterialTheme.colorScheme.error else Color.Unspecified,
                                )
                                r.comment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                r.reply?.let { Text("Your note: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(istTime(r.createdAt, "dd MMM, hh:mm a"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    TextButton({ noteFor = r }) { Text(if (r.reply == null) "Follow up" else "Edit note") }
                                }
                                HorizontalDivider(Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }

                Section("Bills in this period (${d.invoices.size})") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onHistory, Modifier.weight(1f)) { Text("All bills and reprint") }
                        OutlinedButton(onCashCount, Modifier.weight(1f)) { Text("Count the cash") }
                    }
                    if (d.invoices.isNotEmpty()) {
                        OutlinedButton({
                            scope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    File(File(context.cacheDir, "exports").apply { mkdirs() }, "bills.csv").also { it.writeText(buildCsv(d.invoices)) }
                                }
                                QrSheet.share(context, file, "Export bills (CSV)", "text/csv")
                            }
                        }, Modifier.fillMaxWidth()) { Text("Export CSV for your accountant") }
                    }
                    d.invoices.take(15).forEach { inv ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    val file = withContext(Dispatchers.Default) { InvoicePdf.build(context, inv) }
                                    QrSheet.share(context, file, "Share or print the bill")
                                }
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${InvoicePdf.fileLabel(inv)} · Table ${inv.snapshot.table}", fontWeight = FontWeight.Medium)
                                Text("${istTime(inv.snapshot.paidAt, "dd MMM, hh:mm a")} · ${inv.snapshot.method.uppercase()}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(inv.snapshot.bill.total.rupees(), fontWeight = FontWeight.Bold)
                        }
                        HorizontalDivider()
                    }
                    if (d.invoices.size > 15) Text("Showing the latest 15. The CSV has all of them.", style = MaterialTheme.typography.bodySmall)
                    if (d.invoices.isNotEmpty()) Text("Tap a bill to share or reprint it.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton({ reload++ }, Modifier.align(Alignment.CenterHorizontally)) { Text("Refresh") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    noteFor?.let { r ->
        var text by remember(r) { mutableStateOf(r.reply.orEmpty()) }
        AlertDialog(
            onDismissRequest = { noteFor = null },
            title = { Text("Follow-up note") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A private note for you and your team: what you did about it, or who you called.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(text, { text = it.take(500) }, minLines = 2, label = { Text("Note") })
                }
            },
            confirmButton = { TextButton({ scope.launch { runCatching { owner.setReviewNote(r.id, text) }; reload++ }; noteFor = null }) { Text("Save") } },
            dismissButton = { TextButton({ noteFor = null }) { Text("Cancel") } },
        )
    }

    if (showPicker) {
        val picker = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    enabled = picker.selectedStartDateMillis != null && picker.selectedEndDateMillis != null,
                    onClick = {
                        fun day(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate().toEpochDay()
                        customStart = day(picker.selectedStartDateMillis!!)
                        customEnd = day(picker.selectedEndDateMillis!!)
                        range = Range.CUSTOM
                        showPicker = false
                    },
                ) { Text("Apply") }
            },
            dismissButton = { TextButton({ showPicker = false }) { Text("Cancel") } },
        ) { DateRangePicker(state = picker, modifier = Modifier.height(520.dp), title = { Text("Choose dates", Modifier.padding(16.dp)) }) }
    }
}

private fun stars(n: Int?): String = if (n == null) "–" else "★".repeat(n) + "☆".repeat(5 - n)

@Composable
private fun Kpi(label: String, value: String, modifier: Modifier) {
    GlassCard(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        content()
    }
}

/** Column chart built from plain boxes: no chart library needed. */
@Composable
private fun VBars(values: List<Long>, labels: List<String>, color: Color) {
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        values.forEach { v ->
            Box(
                Modifier.weight(1f).fillMaxHeight((v / max).coerceIn(0.03f, 1f))
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(if (v > 0) color else MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        labels.forEach { Text(it, Modifier.weight(1f), fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1, softWrap = false) }
    }
}

@Composable
private fun HBar(label: String, fraction: Float, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(112.dp), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.03f, 1f)).fillMaxHeight().background(color))
        }
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
