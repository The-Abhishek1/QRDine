package com.xcloak.qrdine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.qrdine.data.AddonGroup

/** Extras the guest can pick: Cheese, Spice level, Sauces. A group is attached to dishes in the dish editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonGroupsScreen(vm: MenuViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var groupDialog by remember { mutableStateOf<AddonGroup?>(null) }
    var newGroup by remember { mutableStateOf(false) }
    var addonFor by remember { mutableStateOf<AddonGroup?>(null) }
    var deleteFor by remember { mutableStateOf<AddonGroup?>(null) }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add-on groups", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { newGroup = true }, text = { Text("New group") }, icon = {}) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            s.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (s.groups.isEmpty()) item { Text("No groups yet. Example: group \"Extras\" with Cheese and Butter, pick up to 2. Then attach it to a dish.", style = MaterialTheme.typography.bodyMedium) }
            items(s.groups, key = { it.id }) { g ->
                val addons = s.addons.filter { it.groupId == g.id }
                val usedBy = s.links.count { it.groupId == g.id }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(g.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            (if (g.minSelect > 0) "Required: pick at least ${g.minSelect}, " else "Optional, ") + "pick up to ${g.maxSelect} · used on $usedBy dish(es)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        addons.forEach { a ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${a.name}  +${a.price.toLong().rupees()}", Modifier.weight(1f))
                                Switch(a.isAvailable, { vm.setAddonAvailable(a, it) })
                                TextButton({ vm.deleteAddon(a) }) { Text("✕") }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ addonFor = g }) { Text("Add item") }
                            TextButton({ groupDialog = g }) { Text("Edit") }
                            TextButton({ deleteFor = g }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    if (newGroup || groupDialog != null) {
        val g = groupDialog
        var name by remember(g) { mutableStateOf(g?.name ?: "") }
        var min by remember(g) { mutableStateOf((g?.minSelect ?: 0).toString()) }
        var max by remember(g) { mutableStateOf((g?.maxSelect ?: 1).toString()) }
        val mn = min.toIntOrNull(); val mx = max.toIntOrNull()
        val ok = name.isNotBlank() && mn != null && mx != null && mx >= 1 && mn <= mx
        AlertDialog(
            onDismissRequest = { newGroup = false; groupDialog = null },
            title = { Text(if (g == null) "New group" else "Edit group") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text("Group name (Extras, Spice level)") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(min, { min = it.filter(Char::isDigit).take(2) }, label = { Text("Must pick at least") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        OutlinedTextField(max, { max = it.filter(Char::isDigit).take(2) }, label = { Text("At most") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    }
                    Text("At least 0 = optional. At most 1 = pick one.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = ok, onClick = { if (g == null) vm.addGroup(name, mn!!, mx!!) else vm.updateGroup(g, name, mn!!, mx!!); newGroup = false; groupDialog = null }) { Text("Save") }
            },
            dismissButton = { TextButton({ newGroup = false; groupDialog = null }) { Text("Cancel") } },
        )
    }

    addonFor?.let { g ->
        var name by remember(g) { mutableStateOf("") }
        var price by remember(g) { mutableStateOf("") }
        val paise = if (price.isBlank()) 0 else rupeesToPaise(price)?.toInt()
        AlertDialog(
            onDismissRequest = { addonFor = null },
            title = { Text("Add to ${g.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name (Extra cheese)") }, singleLine = true)
                    OutlinedTextField(price, { price = it.filter { c -> c.isDigit() || c == '.' }.take(7) }, label = { Text("Extra price ₹ (empty = free)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank() && paise != null, onClick = { vm.addAddon(g.id, name, paise!!); addonFor = null }) { Text("Add") } },
            dismissButton = { TextButton({ addonFor = null }) { Text("Cancel") } },
        )
    }

    deleteFor?.let { g ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${g.name}?") },
            text = { Text("The group and its items are removed from every dish. Past orders are not affected.") },
            confirmButton = { TextButton({ vm.deleteGroup(g); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton({ deleteFor = null }) { Text("Cancel") } },
        )
    }
}
