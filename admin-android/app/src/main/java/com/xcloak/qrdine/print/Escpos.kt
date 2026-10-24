package com.xcloak.qrdine.print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import com.xcloak.qrdine.data.InvoiceRow
import com.xcloak.qrdine.data.OrderCard
import com.xcloak.qrdine.data.label
import com.xcloak.qrdine.notify.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Plain ESC/POS over Bluetooth serial, the common language of 58 mm and 80 mm receipt printers.
 * Text is ASCII only (most cheap printers have no rupee or Indian-script glyphs), so Rs is used and other characters become ?.
 * Not verified on physical printers here: test with yours before relying on it.
 */
object Escpos {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private class Doc(private val width: Int) {
        val out = ByteArrayOutputStream()
        init { out.write(byteArrayOf(0x1B, 0x40)) }                           // initialise
        fun align(a: Int) = out.write(byteArrayOf(0x1B, 0x61, a.toByte()))     // 0 left, 1 centre
        fun bold(on: Boolean) = out.write(byteArrayOf(0x1B, 0x45, if (on) 1 else 0))
        fun size(big: Boolean) = out.write(byteArrayOf(0x1D, 0x21, if (big) 0x11 else 0x00))
        private fun ascii(s: String) = s.replace("₹", "Rs").map { if (it.code in 32..126) it else '?' }.joinToString("")
        fun line(s: String = "") { out.write(ascii(s).toByteArray(Charsets.US_ASCII)); out.write(0x0A) }
        fun rule() = line("-".repeat(width))
        fun two(left: String, right: String) {
            val l = ascii(left); val r = ascii(right)
            val gap = (width - l.length - r.length).coerceAtLeast(1)
            line(if (l.length + r.length + 1 > width) l.take(width - r.length - 1) + " " + r else l + " ".repeat(gap) + r)
        }
        fun finish(): ByteArray { out.write(byteArrayOf(0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x42, 0x00)); return out.toByteArray() }
    }

    private fun money(p: Long) = "Rs " + (if (p % 100L == 0L) (p / 100).toString() else "%.2f".format(java.util.Locale.US, p / 100.0))

    fun kot(restaurant: String, card: OrderCard, width: Int): ByteArray {
        val d = Doc(width)
        d.align(1); d.bold(true); d.line(restaurant.take(width)); d.size(true); d.line("TABLE ${card.tableLabel}"); d.size(false); d.bold(false)
        d.line(if (card.order.orderType == "parcel") "PARCEL" else "DINE IN")
        d.align(0); d.rule()
        for (i in card.items.filter { !it.cancelled }) {
            d.bold(true); d.line("${i.qty} x ${i.label()}"); d.bold(false)
            i.instructions?.let { d.line("   * $it") }
        }
        card.order.note?.let { d.rule(); d.bold(true); d.line("NOTE: $it"); d.bold(false) }
        d.rule(); d.line(java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata")).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm", java.util.Locale.ENGLISH)))
        return d.finish()
    }

    fun bill(inv: InvoiceRow, width: Int): ByteArray {
        val s = inv.snapshot; val b = s.bill; val r = s.restaurant
        val d = Doc(width)
        d.align(1); d.bold(true); d.size(true); d.line(r.name.take(width / 2)); d.size(false); d.bold(false)
        r.address?.let { d.line(it.take(width * 2)) }
        listOfNotNull(r.phone?.let { "Ph $it" }, r.gstin?.let { "GSTIN $it" }, r.fssai?.let { "FSSAI $it" }).forEach { d.line(it) }
        d.bold(true); d.line(if (r.gstMode == "regular") "TAX INVOICE" else "BILL"); d.bold(false)
        d.align(0)
        d.two("No ${inv.fy}/${inv.number.toString().padStart(4, '0')}", "Table ${s.table}")
        d.line(java.time.OffsetDateTime.parse(s.paidAt).atZoneSameInstant(java.time.ZoneId.of("Asia/Kolkata")).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", java.util.Locale.ENGLISH)))
        d.rule()
        for (l in b.lines) { d.line("${l.qty} x ${l.name}".take(width)); d.two("   @ ${money(l.price)}", money(l.amount)) }
        d.rule()
        d.two("Subtotal", money(b.subtotal))
        if (b.discount > 0) d.two("Discount" + (b.coupon?.let { " ($it)" } ?: ""), "-" + money(b.discount))
        if (b.serviceCharge > 0) d.two("Service charge", money(b.serviceCharge))
        if (b.tax > 0) { d.two("CGST ${b.gstRate / 2}%", money(b.cgst)); d.two("SGST ${b.gstRate / 2}%", money(b.sgst)) }
        if (b.roundOff != 0L) d.two("Round off", (if (b.roundOff < 0) "-" else "") + money(kotlin.math.abs(b.roundOff)))
        d.rule(); d.bold(true); d.size(true); d.two("TOTAL", money(b.total)); d.size(false); d.bold(false)
        d.line("Paid by ${s.method.uppercase()}")
        d.align(1); d.line("Thank you! Visit again.")
        return d.finish()
    }

    fun testPage(width: Int): ByteArray {
        val d = Doc(width)
        d.align(1); d.bold(true); d.size(true); d.line("QR Dine"); d.size(false); d.bold(false)
        d.line("Printer test"); d.rule(); d.align(0)
        d.two("Left", "Right"); d.line("Rs 1,234.00  OK"); d.rule()
        d.align(1); d.line("If you can read this, printing works.")
        return d.finish()
    }

    fun hasPrinter(context: Context) = Prefs.printerAddress(context) != null

    @SuppressLint("MissingPermission")
    suspend fun send(context: Context, data: ByteArray) = withContext(Dispatchers.IO) {
        val address = Prefs.printerAddress(context) ?: error("No printer selected")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("Bluetooth is not available")
        check(adapter.isEnabled) { "Bluetooth is off" }
        val device = adapter.getRemoteDevice(address)
        adapter.cancelDiscovery()
        device.createRfcommSocketToServiceRecord(SPP).use { socket ->
            socket.connect()
            socket.outputStream.write(data)
            socket.outputStream.flush()
            Thread.sleep(400)           // let the printer drain its buffer before the socket closes
        }
    }

    suspend fun printKot(context: Context, restaurant: String, card: OrderCard) = send(context, kot(restaurant, card, Prefs.paperChars(context)))
    suspend fun printBill(context: Context, inv: InvoiceRow) = send(context, bill(inv, Prefs.paperChars(context)))
}
