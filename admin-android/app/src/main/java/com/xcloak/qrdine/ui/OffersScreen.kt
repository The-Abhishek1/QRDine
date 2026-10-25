package com.xcloak.qrdine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.qrdine.data.Dish
import com.xcloak.qrdine.data.Offer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OffersScreen(vm: MenuViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var createOffer by remember { mutableStateOf(false) }
    var deleteFor by remember { mutableStateOf<Offer?>(null) }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offers", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { createOffer = true }, text = { Text("New offer") }, icon = {}) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            s.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (s.offers.isEmpty()) {
                item { Text("Create a Buy N Get M offer. Guests add both dishes; eligible reward items are discounted automatically.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(s.offers, key = { it.id }) { offer ->
                val buy = s.dishes.firstOrNull { it.id == offer.buyDishId }
                val reward = s.dishes.firstOrNull { it.id == offer.rewardDishId }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(offer.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Buy ${offer.buyQty} ${buy?.name ?: "dish"}, get ${offer.getQty} ${reward?.name ?: "dish"} free",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("Repeats for every qualifying bundle. Customers add the reward item themselves.", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(if (offer.active) "Active" else "Paused", Modifier.weight(1f))
                            Switch(offer.active, { vm.setOfferActive(offer, it) })
                            TextButton({ deleteFor = offer }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    if (createOffer) {
        var name by remember { mutableStateOf("") }
        var buyQty by remember { mutableStateOf("2") }
        var getQty by remember { mutableStateOf("1") }
        var buyDishId by remember { mutableStateOf(s.dishes.firstOrNull()?.id.orEmpty()) }
        var rewardDishId by remember { mutableStateOf(s.dishes.firstOrNull()?.id.orEmpty()) }
        val buy = buyQty.toIntOrNull()
        val get = getQty.toIntOrNull()
        val valid = s.dishes.isNotEmpty() && name.length <= 80 && buy != null && buy in 1..50 && get != null && get in 1..50 &&
            s.dishes.any { it.id == buyDishId } && s.dishes.any { it.id == rewardDishId }

        AlertDialog(
            onDismissRequest = { createOffer = false },
            title = { Text("Create offer") },
            text = {
                Column(
                    Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(name, { name = it.take(80) }, label = { Text("Offer name (optional)") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            buyQty, { buyQty = it.filter(Char::isDigit).take(2) }, label = { Text("Buy quantity") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            singleLine = true, modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            getQty, { getQty = it.filter(Char::isDigit).take(2) }, label = { Text("Free quantity") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            singleLine = true, modifier = Modifier.weight(1f),
                        )
                    }
                    DishPicker("Buy dish", s.dishes, buyDishId) { buyDishId = it }
                    DishPicker("Free dish", s.dishes, rewardDishId) { rewardDishId = it }
                    Text(
                        "Pick the same dish for both fields for a same-item deal. Guests must add the free dish; the cheapest eligible units become free. Offers don't stack on the same units.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid && !s.saving,
                    onClick = {
                        val buyName = s.dishes.first { it.id == buyDishId }.name
                        val rewardName = s.dishes.first { it.id == rewardDishId }.name
                        val offerName = name.trim().ifBlank { "Buy $buy $buyName, get $get $rewardName free" }
                        vm.addOffer(offerName, buy!!, get!!, buyDishId, rewardDishId) { createOffer = false }
                    },
                ) { Text(if (s.saving) "Saving…" else "Create") }
            },
            dismissButton = { TextButton({ createOffer = false }) { Text("Cancel") } },
        )
    }

    deleteFor?.let { offer ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${offer.name}?") },
            text = { Text("This offer will no longer appear to customers. Past orders are not changed.") },
            confirmButton = { TextButton({ vm.deleteOffer(offer); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton({ deleteFor = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DishPicker(label: String, dishes: List<Dish>, selectedId: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(
            dishes.firstOrNull { it.id == selectedId }?.name.orEmpty(),
            {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            dishes.forEach { dish ->
                DropdownMenuItem(text = { Text(dish.name) }, onClick = { onSelect(dish.id); expanded = false })
            }
        }
    }
}
