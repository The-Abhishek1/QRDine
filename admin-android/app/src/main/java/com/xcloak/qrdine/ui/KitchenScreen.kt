package com.xcloak.qrdine.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SoupKitchen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xcloak.qrdine.data.label
import com.xcloak.qrdine.notify.AlertSound
import com.xcloak.qrdine.notify.Prefs
import kotlinx.coroutines.delay

/**
 * Big-text screen for a second phone or tablet in the kitchen: orders that were accepted and are not ready yet, oldest first.
 * Rings when a new order arrives. The screen stays on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KitchenScreen(exitLabel: String, onExit: () -> Unit, vm: OrdersViewModel = viewModel()) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val cooking = snap.accepted.filter { it.order.readyAt == null }.sortedBy { it.order.createdAt }
    val tick by produceState(0L) { while (true) { delay(30_000); value = System.currentTimeMillis() } }

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    var seen by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(cooking.map { it.order.id }) {
        val ids = cooking.map { it.order.id }.toSet()
        val prev = seen
        if (prev != null && ids.any { it !in prev }) {
            if (Prefs.alertSoundEnabled(context)) AlertSound.play(context) else AlertSound.stop()
        }
        seen = ids
    }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.messageShown() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Kitchen · ${cooking.size} to cook", fontWeight = FontWeight.Bold) },
                actions = { TextButton(onExit) { Text(exitLabel) } },
            )
        },
    ) { padding ->
        if (cooking.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                IllustratedEmptyState(
                    "Nothing to cook",
                    "New orders appear here with a sound.",
                    icon = Icons.Default.SoupKitchen,
                )
            }
        } else LazyVerticalGrid(
            GridCells.Adaptive(320.dp), Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(cooking, key = { it.order.id }) { o ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TableTag(o.tableLabel)
                            Column(Modifier.weight(1f)) {
                                Text(if (o.order.orderType == "parcel") "PARCEL" else "DINE IN", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text(ago(o.order.createdAt).also { tick.hashCode() } + (o.order.etaMinutes?.let { " · promised $it min" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        o.items.filter { !it.cancelled }.forEach { i ->
                            Text("${i.qty} × ${i.label()}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            i.instructions?.let { Text("   $it", fontSize = 18.sp, color = MaterialTheme.colorScheme.tertiary) }
                        }
                        o.order.note?.let {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                                Text("NOTE: $it", Modifier.padding(10.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Button({ vm.ready(o.order.id) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                            Text("READY", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
