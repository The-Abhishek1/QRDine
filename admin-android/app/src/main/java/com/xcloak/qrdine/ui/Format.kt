package com.xcloak.qrdine.ui

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val IST: ZoneId = ZoneId.of("Asia/Kolkata")

fun Long.rupees(): String = if (this % 100L == 0L) "₹${this / 100}" else "₹%.2f".format(Locale.US, this / 100.0)

fun ago(iso: String): String = runCatching {
    val m = Duration.between(OffsetDateTime.parse(iso).toInstant(), Instant.now()).toMinutes()
    when { m < 1 -> "just now"; m < 60 -> "$m min ago"; else -> "${m / 60} h ${m % 60} min ago" }
}.getOrDefault("")

fun istTime(iso: String, pattern: String = "dd MMM yyyy, hh:mm a"): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(IST).format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
}.getOrDefault(iso)

/** "120" or "120.50" rupees to paise; null if it is not a valid positive amount with at most 2 decimals. */
fun rupeesToPaise(text: String): Long? = runCatching {
    val v = java.math.BigDecimal(text.trim())
    if (v.signum() <= 0 || v.scale() > 2) null else v.movePointRight(2).toLong()
}.getOrNull()

fun dateLabel(value: String?): String = when {
    value.isNullOrBlank() -> "–"
    value.length == 10 -> runCatching { java.time.LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)) }.getOrDefault(value)
    else -> istTime(value, "dd MMM yyyy")
}

object Csv {
    fun cell(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
    fun rupees(p: Long) = "%.2f".format(Locale.US, p / 100.0)
}

val LANG_NAMES = mapOf("hi" to "Hindi", "kn" to "Kannada", "mr" to "Marathi", "ta" to "Tamil", "te" to "Telugu")
val TIME_OF_DAY = Regex("^([01]?\\d|2[0-3])(:[0-5]\\d)?$")

fun normalizeTime(s: String): String? {
    val t = s.trim()
    if (t.isBlank()) return null
    if (t.matches(Regex("^([01]?\\d|2[0-3])$"))) {
        return "%02d:00".format(t.toInt())
    }
    if (t.matches(Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$"))) {
        val parts = t.split(":")
        return "%02d:%s".format(parts[0].toInt(), parts[1])
    }
    return null
}

/** Like rupeesToPaise but zero is allowed (an empty drawer is a valid count). */
fun amountToPaise(text: String): Long? = runCatching {
    val v = java.math.BigDecimal(text.trim())
    if (v.signum() < 0 || v.scale() > 2) null else v.movePointRight(2).toLong()
}.getOrNull()
