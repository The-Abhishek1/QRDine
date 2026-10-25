package com.xcloak.qrdine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.xcloak.qrdine.data.DiningTable
import kotlinx.coroutines.delay

@Composable
private fun StatusChip(status: String) {
    val (label, bg, fg) = when (status) {
        "active" -> Triple("Open", Color(0xFFD7F0EA), Color(0xFF0B5F53))
        "bill_requested" -> Triple("Bill requested", Color(0xFFF2B632), Color(0xFF14231F))
        else -> Triple("Closed", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(
        label, color = fg, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TablesScreen(vm: TablesViewModel, bottomBar: @Composable () -> Unit, onOpenBill: (String, String) -> Unit, canManage: Boolean = true) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showRotate by remember { mutableStateOf(false) }
    var openFor by remember { mutableStateOf<DiningTable?>(null) }
    var voidFor by remember { mutableStateOf<DiningTable?>(null) }
    var removeFor by remember { mutableStateOf<DiningTable?>(null) }
    var moveFor by remember { mutableStateOf<DiningTable?>(null) }

    // refresh now and every 8 s while the screen is visible (realtime arrives with the orders milestone)
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { vm.refresh(); delay(8000) }
        }
    }
    LaunchedEffect(s.shareFile) { s.shareFile?.let { QrSheet.share(context, it); vm.shareConsumed() } }
    LaunchedEffect(s.message) { s.message?.let { snack.showSnackbar(it); vm.messageShown() } }

    Scaffold(
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text("Tables", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (canManage) IconButton({ menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Add tables") }, onClick = { menuOpen = false; showAdd = true })
                        DropdownMenuItem(text = { Text("QR codes (PDF)") }, onClick = { menuOpen = false; vm.prepareQrSheet(context) })
                        DropdownMenuItem(text = { Text("Replace all QR codes") }, onClick = { menuOpen = false; showRotate = true })
                    }
                },
            )
        },
        floatingActionButton = {
            if (s.tables.isNotEmpty() && canManage) ExtendedFloatingActionButton(
                onClick = { vm.prepareQrSheet(context) }, text = { Text("Print QR codes") }, icon = { Icon(Icons.Default.Add, null) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                s.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                s.tables.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ScreenIntro("GET STARTED", "Set up your floor", "Add tables, print their QR codes, and place one at each table.")
                    Button({ showAdd = true }) { Text("Add tables") }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "floor-overview") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ScreenIntro("FLOOR OVERVIEW", "Your tables", "Open a table to begin a guest session.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MetricCard("Total", s.tables.size.toString(), Modifier.weight(1f))
                                MetricCard("Open", s.tables.count { it.status != "closed" }.toString(), Modifier.weight(1f))
                                MetricCard("Bill ready", s.tables.count { it.status == "bill_requested" }.toString(), Modifier.weight(1f))
                            }
                        }
                    }
                    items(s.tables, key = { it.id }) { t ->
                        var rowMenu by remember { mutableStateOf(false) }
                        GlassCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(t.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    StatusChip(t.status)
                                }
                                if (t.status == "closed") {
                                    FilledTonalButton({ openFor = t }, enabled = !s.busy) { Text("Open table") }
                                    Box {
                                        if (canManage) IconButton({ rowMenu = true }) { Icon(Icons.Default.MoreVert, "Table options") }
                                        DropdownMenu(rowMenu, { rowMenu = false }) {
                                            DropdownMenuItem(text = { Text("Remove table") }, onClick = { rowMenu = false; removeFor = t })
                                        }
                                    }
                                } else {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Button({ t.activeSessionId?.let { onOpenBill(it, t.label) } }, enabled = t.activeSessionId != null) {
                                            Text(if (t.status == "bill_requested") "Collect payment" else "View bill")
                                        }
                                        TextButton({ moveFor = t }, enabled = !s.busy) { Text("Move or merge") }
                                        TextButton({ voidFor = t }, enabled = !s.busy) { Text("Cancel session") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }

    if (showAdd) {
        var n by remember { mutableStateOf("5") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add tables") },
            text = {
                OutlinedTextField(
                    n, { n = it.filter(Char::isDigit).take(2) }, label = { Text("How many tables?") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(enabled = (n.toIntOrNull() ?: 0) in 1..50, onClick = { vm.addTables(n.toInt()); showAdd = false }) { Text("Add") }
            },
            dismissButton = { TextButton({ showAdd = false }) { Text("Cancel") } },
        )
    }

    openFor?.let { t ->
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { openFor = null },
            title = { Text("Open table ${t.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Guests can order as soon as it is open. Add a 4-digit code if you want them to enter one first.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        pin, { pin = it.filter(Char::isDigit).take(4) }, label = { Text("Table code (optional)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = pin.isEmpty() || pin.length == 4, onClick = { vm.openTable(t, pin.ifEmpty { null }); openFor = null }) { Text("Open") }
            },
            dismissButton = { TextButton({ openFor = null }) { Text("Cancel") } },
        )
    }

    voidFor?.let { t ->
        AlertDialog(
            onDismissRequest = { voidFor = null },
            title = { Text("Cancel session on ${t.label}?") },
            text = { Text("Use this if the table was opened by mistake. It only works while the table has no orders.") },
            confirmButton = { TextButton({ vm.voidSession(t); voidFor = null }) { Text("Cancel session") } },
            dismissButton = { TextButton({ voidFor = null }) { Text("Keep open") } },
        )
    }

    removeFor?.let { t ->
        AlertDialog(
            onDismissRequest = { removeFor = null },
            title = { Text("Remove ${t.label}?") },
            text = { Text("Its printed QR code will stop working. Tables with past orders cannot be removed.") },
            confirmButton = { TextButton({ vm.deleteTable(t); removeFor = null }) { Text("Remove") } },
            dismissButton = { TextButton({ removeFor = null }) { Text("Cancel") } },
        )
    }

    moveFor?.let { from ->
        val others = s.tables.filter { it.id != from.id }
        AlertDialog(
            onDismissRequest = { moveFor = null },
            title = { Text("Move or merge ${from.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("A free table: the guests move there. An open table: both tables become one bill.", style = MaterialTheme.typography.bodySmall)
                    others.forEach { o ->
                        OutlinedButton({ vm.moveOrMerge(from, o); moveFor = null }, Modifier.fillMaxWidth()) {
                            Text(if (o.status == "closed") "Move to ${o.label}" else "Merge into ${o.label}")
                        }
                    }
                    if (others.isEmpty()) Text("There are no other tables.")
                }
            },
            confirmButton = { TextButton({ moveFor = null }) { Text("Close") } },
        )
    }

    if (showRotate) {
        AlertDialog(
            onDismissRequest = { showRotate = false },
            title = { Text("Replace all QR codes?") },
            text = { Text("Every QR code already printed stops working. Do this only if a code was misused, then print and place the new ones.") },
            confirmButton = { TextButton({ vm.rotateQr(); showRotate = false }) { Text("Replace") } },
            dismissButton = { TextButton({ showRotate = false }) { Text("Cancel") } },
        )
    }
}
