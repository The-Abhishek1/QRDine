package com.xcloak.qrdine.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.xcloak.qrdine.data.InvoiceRow
import java.io.File

/** A5 bill/invoice built on the phone from the stored invoice snapshot. Paginates if the bill is long. */
object InvoicePdf {
    private const val W = 420
    private const val H = 595

    fun fileLabel(inv: InvoiceRow) = "${inv.fy}/${inv.number.toString().padStart(4, '0')}"

    fun build(context: Context, inv: InvoiceRow): File {
        val snap = inv.snapshot
        val b = snap.bill
        val r = snap.restaurant
        val doc = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        var canvas = page.canvas
        var y = 40f

        fun ensure(space: Float) {
            if (y + space > H - 40f) {
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
                canvas = page.canvas
                y = 40f
            }
        }
        fun center(text: String, size: Float, bold: Boolean = false) {
            if (text.isBlank()) return
            ensure(size + 6)
            paint.textSize = size; paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; paint.textAlign = Paint.Align.CENTER
            canvas.drawText(text, W / 2f, y, paint); y += size + 5
        }
        fun rule() { ensure(10f); paint.strokeWidth = 0.8f; canvas.drawLine(30f, y, W - 30f, y, paint); y += 12 }
        fun row(left: String, right: String, bold: Boolean = false, size: Float = 11f) {
            ensure(size + 6)
            paint.textSize = size; paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textAlign = Paint.Align.LEFT; canvas.drawText(left, 30f, y, paint)
            paint.textAlign = Paint.Align.RIGHT; canvas.drawText(right, W - 30f, y, paint)
            y += size + 5
        }

        center(r.name, 18f, true)
        center(r.address.orEmpty(), 10f)
        center(listOfNotNull(r.phone?.let { "Ph: $it" }, r.gstin?.let { "GSTIN: $it" }).joinToString("   "), 10f)
        center(r.fssai?.let { "FSSAI: $it" }.orEmpty(), 10f)
        y += 6
        center(if (r.gstMode == "regular") "TAX INVOICE" else "BILL", 13f, true)
        row("No: ${fileLabel(inv)}", "Table ${snap.table}")
        row(istTime(snap.paidAt), "Paid: ${snap.method.uppercase()}", size = 10f)
        rule()

        paint.textSize = 10f; paint.typeface = Typeface.DEFAULT_BOLD
        ensure(16f)
        paint.textAlign = Paint.Align.LEFT; canvas.drawText("Item", 30f, y, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Qty", 250f, y, paint); canvas.drawText("Rate", 320f, y, paint); canvas.drawText("Amount", W - 30f, y, paint)
        y += 14
        for (l in b.lines) {
            ensure(16f)
            paint.textSize = 10.5f; paint.typeface = Typeface.DEFAULT
            paint.textAlign = Paint.Align.LEFT; canvas.drawText(l.name.take(26), 30f, y, paint)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(l.qty.toString(), 250f, y, paint)
            canvas.drawText(l.price.rupees(), 320f, y, paint)
            canvas.drawText(l.amount.rupees(), W - 30f, y, paint)
            y += 15
        }
        rule()
        row("Subtotal", b.subtotal.rupees())
        if (b.discount > 0) row("Discount${b.coupon?.let { " ($it)" }.orEmpty()}", "-${b.discount.rupees()}")
        if (b.serviceCharge > 0) row("Service charge (${b.serviceChargePct}%)", b.serviceCharge.rupees())
        if (b.tax > 0) {
            row("CGST (${b.gstRate / 2}%)", b.cgst.rupees())
            row("SGST (${b.gstRate / 2}%)", b.sgst.rupees())
        }
        if (b.roundOff != 0L) row("Round off", (if (b.roundOff < 0) "-" else "") + kotlin.math.abs(b.roundOff).rupees())
        rule()
        row("TOTAL", b.total.rupees(), bold = true, size = 14f)
        y += 10
        center("Thank you! Visit again.", 11f)
        doc.finishPage(page)

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "bill-${inv.fy}-${inv.number}.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }
}
