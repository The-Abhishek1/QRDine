package com.xcloak.qrdine.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * One live copy of "what needs the staff now", shared by the Orders screen, the kitchen screen and the alert service.
 * Realtime change events trigger a reload; a 20 s poll covers dropped sockets (Android Doze).
 * Loads run one at a time, so a token refresh is never started twice at once.
 */
class OrdersStore(
    private val repo: OrdersRepository,
    private val sb: SupabaseClient,
    private val scope: CoroutineScope,
) {
    private val _snapshot = MutableStateFlow(OwnerSnapshot())
    val snapshot: StateFlow<OwnerSnapshot> = _snapshot.asStateFlow()
    private val loadLock = Mutex()

    private var job: Job? = null
    private var restaurantId: String? = null

    @Synchronized
    fun start(id: String) {
        if (restaurantId == id && job?.isActive == true) return
        stopInternal()
        restaurantId = id
        job = scope.launch { run(id) }
    }

    @Synchronized
    fun stop() {
        stopInternal()
        _snapshot.value = OwnerSnapshot()
    }

    private fun stopInternal() { job?.cancel(); job = null; restaurantId = null }

    private fun looksLikeAuthError(e: Exception): Boolean {
        val m = e.message ?: return false
        return "permission denied" in m || "JWT" in m || "401" in m
    }

    private suspend fun loadWithAuthRetry(): OwnerSnapshot {
        sb.auth.awaitInitialization()
        if (sb.auth.currentSessionOrNull() == null) throw IllegalStateException("not_signed_in")
        return try {
            repo.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!looksLikeAuthError(e)) throw e
            sb.auth.refreshCurrentSession()   // token expired or was not attached: get a fresh one, try once more
            repo.load()
        }
    }

    private fun describe(e: Exception): String {
        if (e.message == "not_signed_in") return "Not signed in. Please sign in again."
        if (looksLikeAuthError(e)) {
            val role = sb.auth.currentAccessTokenOrNull()?.let { Jwt.claim(it, "role") } ?: "none"
            return "Session problem (role: $role). Sign out and sign in again."
        }
        return (e.message ?: "Could not refresh").take(200)
    }

    suspend fun refresh() {
        loadLock.withLock {
            try {
                _snapshot.value = loadWithAuthRetry()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _snapshot.update { it.copy(error = describe(e)) }
            }
        }
    }

    private suspend fun run(id: String) = coroutineScope {
        refresh()
        val channel = sb.channel("owner-$id")
        val changes = merge(
            channel.postgresChangeFlow<PostgresAction>(schema = "public") { table = "orders" },
            channel.postgresChangeFlow<PostgresAction>(schema = "public") { table = "service_requests" },
            channel.postgresChangeFlow<PostgresAction>(schema = "public") { table = "sessions" },
        )
        launch { changes.collectLatest { delay(250); refresh() } }
        try { channel.subscribe() } catch (_: Exception) { /* the poll below keeps things correct */ }
        try {
            while (isActive) { delay(20_000); refresh() }
        } finally {
            withContext(NonCancellable) { runCatching { channel.unsubscribe(); sb.realtime.removeChannel(channel) } }
        }
    }
}
