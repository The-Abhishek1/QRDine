package com.xcloak.qrdine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.qrdine.Diagnostics
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.OrderCard
import com.xcloak.qrdine.notify.Prefs
import com.xcloak.qrdine.print.Escpos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OrdersUiState(val busy: Boolean = false, val message: String? = null)

class OrdersViewModel : ViewModel() {
    private val c = QrDineApp.container
    val snapshot = c.ordersStore.snapshot

    private val _ui = MutableStateFlow(OrdersUiState())
    val ui: StateFlow<OrdersUiState> = _ui

    private fun friendly(e: Exception): String {
        val m = e.message ?: return "Something went wrong"
        return when {
            "order_not_pending" in m -> "That order was already handled (maybe on another device)."
            "order_not_accepted" in m -> "That order is not in the cooking list."
            "order_not_found" in m -> "You are not allowed to do that, or the order no longer exists."
            "reason_required" in m -> "Please pick or type a reason."
            "bad_eta" in m -> "Pick a wait time between 1 and 240 minutes."
            else -> m.take(160)
        }
    }

    private fun act(block: suspend () -> Unit) {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { block(); c.ordersStore.refresh() }
            catch (e: Exception) {
                Diagnostics.record(QrDineApp.appContext, e, "orders")
                _ui.update { it.copy(message = friendly(e)) }
                c.ordersStore.refresh()
            }
            finally { _ui.update { it.copy(busy = false) } }
        }
    }

    private fun printTicket(card: OrderCard) {
        val ctx = QrDineApp.appContext
        viewModelScope.launch {
            try {
                val name = runCatching {
                    c.auth.restaurantId()?.let { c.owner.restaurant(it).name }.orEmpty()
                }.getOrDefault("")
                Escpos.printKot(ctx, name, card)
            } catch (e: Exception) {
                Diagnostics.record(QrDineApp.appContext, e, "printer")
                _ui.update { it.copy(message = "Could not print: ${(e.message ?: "printer error").take(100)}") }
            }
        }
    }

    fun accept(card: OrderCard, eta: Int) {
        act { c.orders.accept(card.order.id, eta) }
        val ctx = QrDineApp.appContext
        if (Prefs.autoKot(ctx) && Escpos.hasPrinter(ctx)) printTicket(card)
    }

    fun reject(orderId: String, reason: String) = act { c.orders.reject(orderId, reason) }
    fun ready(orderId: String) = act { c.orders.markReady(orderId) }
    fun served(orderId: String) = act { c.orders.complete(orderId) }
    fun resolve(requestId: String) = act { c.orders.resolveRequest(requestId) }
    fun print(card: OrderCard) = printTicket(card)
    fun setOpen(open: Boolean) = act {
        val rid = c.auth.restaurantId() ?: error("Not signed in")
        c.orders.setOpen(rid, open)
    }
    fun messageShown() = _ui.update { it.copy(message = null) }
}
