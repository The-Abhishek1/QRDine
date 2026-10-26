package com.xcloak.qrdine

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object Diagnostics {
    private const val TAG = "QrDineDiagnostics"
    private const val PREFS = "diagnostics"
    private const val QUEUE = "pending_reports"
    private const val CURRENT_RESTAURANT = "current_restaurant_id"
    private const val REPORTING_ENABLED = "reporting_enabled"
    private val lock = Any()

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                record(context, error, "app", "crash")
            } catch (loggingError: Exception) {
                Log.e(TAG, "Could not persist crash report.", loggingError)
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    fun setRestaurantId(context: Context, restaurantId: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(CURRENT_RESTAURANT, restaurantId)
            .apply()
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(REPORTING_ENABLED, false)

    fun pendingCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return runCatching { JSONArray(prefs.getString(QUEUE, "[]")).length() }.getOrDefault(0)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit().putBoolean(REPORTING_ENABLED, enabled)
        if (!enabled) editor.remove(QUEUE)
        editor.apply()
    }

    fun clearPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(QUEUE)
            .apply()
    }

    fun record(context: Context, error: Throwable, area: String, kind: String = "error") {
        if (!isEnabled(context)) return
        val restaurantId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CURRENT_RESTAURANT, null)
        val entry = JSONObject()
            .put("event_id", UUID.randomUUID().toString())
            .put("restaurant_id", restaurantId)
            .put("kind", if (kind == "crash") "crash" else "error")
            .put("error_type", error.javaClass.simpleName.filter { it.isLetterOrDigit() || it in "_.$" }.take(120).ifBlank { "Exception" })
            .put("area", area.filter { it.isLowerCase() || it == '_' }.take(40).ifBlank { "app" })
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("os_version", "Android ${Build.VERSION.RELEASE}".take(30))
            .put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}".filter { it.isLetterOrDigit() || it in " ._-" }.take(80).ifBlank { "Android device" })
            .put("occurred_at", java.time.Instant.now().toString())
            .put("frames", JSONArray().apply {
                error.stackTrace.take(8).forEach { frame ->
                    val safe = "${frame.className}.${frame.methodName}:${frame.lineNumber}"
                        .filter { it.isLetterOrDigit() || it in "_.$<>:/-" }
                        .take(180)
                    if (safe.isNotBlank()) put(safe)
                }
            })

        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val reports = runCatching { JSONArray(prefs.getString(QUEUE, "[]")) }.getOrDefault(JSONArray())
            reports.put(entry)
            while (reports.length() > 50) reports.remove(0)
            val edit = prefs.edit().putString(QUEUE, reports.toString())
            if (kind == "crash") edit.commit() else edit.apply()
        }
        Log.e(TAG, "Recorded ${entry.optString("kind")} in ${entry.optString("area")}: ${entry.optString("error_type")}")
    }

    suspend fun flush(context: Context, client: SupabaseClient) {
        if (!isEnabled(context)) return
        val queued = synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val allReports = runCatching { JSONArray(prefs.getString(QUEUE, "[]")) }.getOrDefault(JSONArray())
            val currentRestaurantId = prefs.getString(CURRENT_RESTAURANT, null)
            val matchingReports = JSONArray()
            for (index in 0 until allReports.length()) {
                val report = allReports.optJSONObject(index) ?: continue
                val reportRestaurantId = if (report.isNull("restaurant_id")) null else report.optString("restaurant_id")
                if (reportRestaurantId == null || reportRestaurantId == currentRestaurantId) matchingReports.put(report)
            }
            if (matchingReports.length() != allReports.length()) {
                prefs.edit().putString(QUEUE, matchingReports.toString()).apply()
            }
            matchingReports
        }
        if (queued.length() == 0) return

        val batch = (0 until minOf(queued.length(), 20)).map { queued.getJSONObject(it) }
        val response = try {
            client.functions.invoke("app-diagnostics") {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("action", "report")
                    put("reports", buildJsonArray {
                        batch.forEach { item ->
                            add(buildJsonObject {
                                put("event_id", item.getString("event_id"))
                                if (!item.isNull("restaurant_id")) put("restaurant_id", item.getString("restaurant_id"))
                                put("kind", item.getString("kind"))
                                put("error_type", item.getString("error_type"))
                                put("area", item.getString("area"))
                                put("app_version", item.getString("app_version"))
                                put("os_version", item.getString("os_version"))
                                put("device_model", item.getString("device_model"))
                                put("occurred_at", item.getString("occurred_at"))
                                put("frames", buildJsonArray {
                                    val frames = item.getJSONArray("frames")
                                    for (index in 0 until frames.length()) add(kotlinx.serialization.json.JsonPrimitive(frames.getString(index)))
                                })
                            })
                        }
                    })
                }.toString())
            }.bodyAsText()
        } catch (error: Exception) {
            Log.e(TAG, "Could not upload diagnostics; keeping reports queued.", error)
            return
        }
        val accepted = runCatching {
            Json.parseToJsonElement(response).jsonObject["ok"]?.jsonPrimitive?.content == "true"
        }.getOrDefault(false)
        if (!accepted) {
            Log.e(TAG, "Diagnostics endpoint did not accept the queued reports.")
            return
        }

        val submittedIds = batch.map { it.getString("event_id") }.toSet()
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val current = runCatching { JSONArray(prefs.getString(QUEUE, "[]")) }.getOrDefault(JSONArray())
            val remaining = JSONArray()
            for (index in 0 until current.length()) {
                val item = current.optJSONObject(index) ?: continue
                if (item.optString("event_id") !in submittedIds) remaining.put(item)
            }
            prefs.edit().putString(QUEUE, remaining.toString()).apply()
        }
    }
}
