package com.xcloak.qrdine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.InvoiceRow
import com.xcloak.qrdine.print.Escpos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Every bill, searchable by bill number, table, amount, payment method or dish. Tap one to reprint it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = QrDineApp.container.owner
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var days by rememberSaveable { mutableIntStateOf(7) }
    var query by rememberSaveable { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<InvoiceRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<InvoiceRow?>(null) }
    BackHandler(onBack = onClose)

    LaunchedEffect(days) {
        loading = true
        try {
            val today = LocalDate.now(IST)
            rows = owner.invoices(today.minusDays((days - 1).toLong()).atStartOfDay(IST).toInstant().toString(), today.plusDays(1).atStartOfDay(IST).toInstant().toString())
            error = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = (e.message ?: "Could not load bills").take(160) }
        finally { loading = false }
    }

    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) rows else rows.filter { r ->
        InvoicePdf.fileLabel(r).lowercase().contains(q) || r.snapshot.table.lowercase() == q || r.snapshot.method.contains(q) ||
            (r.snapshot.bill.total / 100).toString() == q || r.snapshot.bill.lines.any { it.name.lowercase().contains(q) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("All bills", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(16.dp, 8.dp), label = { Text("Search bill no, table, amount, dish") }, singleLine = true)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "Today", 7 to "7 days", 30 to "30 days", 90 to "90 days").forEach { (d, l) -> FilterChip(days == d, { days = d }, label = { Text(l) }) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            Text("${shown.size} bill(s) · ${shown.sumOf { it.snapshot.bill.total }.rupees()}", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)) {
                items(shown, key = { it.id }) { inv ->
                    Row(Modifier.fillMaxWidth().clickable { detail = inv }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${InvoicePdf.fileLabel(inv)} · Table ${inv.snapshot.table}", fontWeight = FontWeight.Medium)
                            Text("${istTime(inv.snapshot.paidAt, "dd MMM, hh:mm a")} · ${inv.snapshot.method.uppercase()}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(inv.snapshot.bill.total.rupees(), fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    detail?.let { inv ->
        val b = inv.snapshot.bill
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text("Bill ${InvoicePdf.fileLabel(inv)}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Table ${inv.snapshot.table} · ${istTime(inv.snapshot.paidAt)} · ${inv.snapshot.method.uppercase()}", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    b.lines.forEach { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${it.qty} × ${it.name}", Modifier.weight(1f)); Text(it.amount.rupees()) } }
                    HorizontalDivider()
                    if (b.discount > 0) Text("Discount: -${b.discount.rupees()}")
                    if (b.tax > 0) Text("GST: ${b.tax.rupees()}")
                    Text("Total ${b.total.rupees()}", fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                TextButton({
                    scope.launch {
                        val file = withContext(Dispatchers.Default) { InvoicePdf.build(context, inv) }
                        QrSheet.share(context, file, "Share or print the bill")
                    }
                }) { Text("PDF") }
            },
            dismissButton = {
                Row {
                    if (Escpos.hasPrinter(context)) TextButton({
                        scope.launch { try { Escpos.printBill(context, inv) } catch (e: Exception) { snackbar.showSnackbar("Could not print: ${(e.message ?: "printer error").take(100)}") } }
                    }) { Text("Print") }
                    TextButton({ detail = null }) { Text("Close") }
                }
            },
        )
    }
}
