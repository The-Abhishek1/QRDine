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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.CashCount
import kotlinx.coroutines.launch
import java.time.LocalDate

/** End of day: opening float + today's cash sales = what the drawer should hold. Enter what you counted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashCountScreen(onClose: () -> Unit) {
    val owner = QrDineApp.container.owner
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val today = remember { LocalDate.now(IST) }
    var cashSales by remember { mutableStateOf<Long?>(null) }
    var counts by remember { mutableStateOf<List<CashCount>>(emptyList()) }
    var opening by remember { mutableStateOf("") }
    var counted by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    suspend fun load() {
        runCatching {
            val from = today.atStartOfDay(IST).toInstant().toString()
            val to = today.plusDays(1).atStartOfDay(IST).toInstant().toString()
            cashSales = owner.stats(from, to).cash
            counts = owner.cashCounts()
            counts.firstOrNull { it.day == today.toString() }?.let { c ->
                if (opening.isEmpty()) opening = (c.openingFloat / 100.0).toString().removeSuffix(".0")
            }
        }.onFailure { snackbar.showSnackbar((it.message ?: "Could not load").take(120)) }
    }
    LaunchedEffect(Unit) { load() }

    val openingPaise = if (opening.isBlank()) 0L else amountToPaise(opening)
    val countedPaise = if (counted.isBlank()) null else amountToPaise(counted)
    val expected = (openingPaise ?: 0L) + (cashSales ?: 0L)
    val diff = (countedPaise ?: 0L) - expected

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Count the cash", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Today, ${dateLabel(today.toString())}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Cash sales today: ${cashSales?.rupees() ?: "…"}")
                    Text("Drawer should hold: ${expected.rupees()}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }
            OutlinedTextField(opening, { opening = it.filter { c -> c.isDigit() || c == '.' }.take(9) }, label = { Text("Opening float (₹ in the drawer this morning)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(counted, { counted = it.filter { c -> c.isDigit() || c == '.' }.take(9) }, label = { Text("Cash you counted (₹)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            if (counted.isNotBlank() && countedPaise != null) {
                Text(
                    when { diff == 0L -> "Matches. 🎉"; diff > 0 -> "Over by ${diff.rupees()}"; else -> "Short by ${(-diff).rupees()}" },
                    fontWeight = FontWeight.Bold, color = if (diff == 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(note, { note = it.take(300) }, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                {
                    saving = true
                    scope.launch {
                        try { owner.closeDay(today.toString(), openingPaise ?: 0L, countedPaise ?: 0L, note); snackbar.showSnackbar("Saved"); load() }
                        catch (e: Exception) { snackbar.showSnackbar((e.message ?: "Could not save").take(120)) }
                        finally { saving = false }
                    }
                },
                enabled = !saving && cashSales != null && countedPaise != null && counted.isNotBlank() && openingPaise != null,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Save today's count") }

            HorizontalDivider()
            Text("Earlier counts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (counts.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall)
            counts.take(30).forEach { c ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(dateLabel(c.day), fontWeight = FontWeight.Medium)
                        Text("Expected ${c.expectedCash.rupees()} · counted ${c.countedCash.rupees()}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (c.difference == 0L) "OK" else (if (c.difference > 0) "+" else "-") + kotlin.math.abs(c.difference).rupees(),
                        color = if (c.difference == 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
