package com.xcloak.qrdine.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.DiningTable
import com.xcloak.qrdine.data.TableRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class TablesUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val restaurantName: String = "",
    val tables: List<DiningTable> = emptyList(),
    val message: String? = null,
    val shareFile: File? = null,
)

class TablesViewModel(restaurantId: String) : ViewModel() {
    private val sb = QrDineApp.container.supabase
    private val repo = TableRepository(sb, restaurantId)
    private val auth = QrDineApp.container.auth

    private val _state = MutableStateFlow(TablesUiState())
    val state: StateFlow<TablesUiState> = _state

    private fun friendly(e: Exception): String {
        val m = e.message ?: return "Something went wrong"
        return when {
            "session_has_orders" in m -> "This table has orders. It will be closed by billing instead (coming soon)."
            "table_not_closed" in m -> "That table is already open."
            "table_not_active" in m -> "That table has no open session."
            "bad_pin" in m -> "The table code must be exactly 4 digits."
            "table_not_closed" in m || "same_session" in m -> "Pick a different table."
            "session_not_found" in m -> "You are not allowed to do that, or the table was closed."
            "duplicate key" in m -> "A table with that label already exists."
            else -> m.take(160)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val name = if (_state.value.restaurantName.isBlank()) runCatching { auth.restaurant().name }.getOrDefault("") else _state.value.restaurantName
                _state.update { it.copy(loading = false, restaurantName = name, tables = repo.tables()) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = friendly(e)) }
            }
        }
    }

    private fun act(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { block(); _state.update { it.copy(busy = false) }; refresh() }
            catch (e: Exception) { _state.update { it.copy(busy = false, message = friendly(e)) } }
        }
    }

    fun addTables(count: Int) = act { repo.addTables(count.coerceIn(1, 50), _state.value.tables) }
    fun deleteTable(t: DiningTable) = act { repo.deleteTable(t.id) }
    fun openTable(t: DiningTable, pin: String?) = act { repo.openTable(t.id, pin) }
    fun voidSession(t: DiningTable) = act { repo.voidSession(t.id) }

    /** A free target table: the party moves. An open one: the two tables become one bill. */
    fun moveOrMerge(from: DiningTable, to: DiningTable) = act {
        val sid = from.activeSessionId ?: error("table_not_active")
        if (to.status == "closed") repo.moveSession(sid, to.id)
        else repo.mergeSessions(sid, to.activeSessionId ?: error("table_not_active"))
    }
    fun rotateQr() = act {
        repo.rotateQr(_state.value.tables)
        _state.update { it.copy(message = "New QR codes are active. Print them again; old ones no longer work.") }
    }

    fun prepareQrSheet(context: Context) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val items = repo.qrLinks()
                if (items.isEmpty()) { _state.update { it.copy(busy = false, message = "Add tables first.") }; return@launch }
                val file = withContext(Dispatchers.Default) { QrSheet.build(context, _state.value.restaurantName, items) }
                _state.update { it.copy(busy = false, shareFile = file) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "Could not create QR codes: ${friendly(e)}") }
            }
        }
    }

    fun shareConsumed() = _state.update { it.copy(shareFile = null) }
    fun messageShown() = _state.update { it.copy(message = null) }

    class Factory(private val restaurantId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TablesViewModel(restaurantId) as T
    }
}
