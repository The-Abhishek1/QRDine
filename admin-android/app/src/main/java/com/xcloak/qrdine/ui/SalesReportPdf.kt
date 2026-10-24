package com.xcloak.qrdine.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.xcloak.qrdine.data.Stats
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A4 sales summary for a date range, built on the phone from the same numbers as the Insights screen. */
object SalesReportPdf {
    private const val W = 595
    private const val H = 842
    private val dayFmt = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)

    fun build(context: Context, restaurant: String, start: LocalDate, end: LocalDate, st: Stats, bills: Int): File {
        val doc = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        var canvas = page.canvas
        var y = 50f

        fun ensure(space: Float) {
            if (y + space > H - 50f) {
                doc.finishPage(page); pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
                canvas = page.canvas; y = 50f
            }
        }
        fun text(t: String, size: Float, bold: Boolean = false, x: Float = 40f, align: Paint.Align = Paint.Align.LEFT) {
            ensure(size + 6)
            paint.textSize = size; paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; paint.textAlign = align
            canvas.drawText(t, x, y, paint); y += size + 6
        }
        fun row(a: String, b: String = "", c: String = "", d: String = "", bold: Boolean = false) {
            ensure(18f)
            paint.textSize = 11f; paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textAlign = Paint.Align.LEFT; canvas.drawText(a.take(34), 40f, y, paint)
            paint.textAlign = Paint.Align.RIGHT
            if (b.isNotEmpty()) canvas.drawText(b, 340f, y, paint)
            if (c.isNotEmpty()) canvas.drawText(c, 440f, y, paint)
            if (d.isNotEmpty()) canvas.drawText(d, W - 40f, y, paint)
            y += 17f
        }
        fun rule() { ensure(10f); paint.strokeWidth = 0.8f; canvas.drawLine(40f, y - 4, W - 40f, y - 4, paint); y += 8 }
        fun heading(t: String) { y += 10; text(t, 13f, true); rule() }

        text(restaurant.ifBlank { "Sales summary" }, 20f, true)
        text("Sales summary", 13f)
        text(if (start == end) start.format(dayFmt) else "${start.format(dayFmt)} to ${end.format(dayFmt)}", 12f)
        text("Generated ${istTime(java.time.Instant.now().toString())}", 9f)

        heading("Overview")
        row("Revenue (including GST)", "", "", st.revenue.rupees(), bold = true)
        row("Revenue without GST", "", "", (st.revenue - st.tax).rupees())
        row("GST collected", "", "", st.tax.rupees())
        row("Discounts given", "", "", st.discounts.rupees())
        row("Paid bills", "", "", bills.toString())
        row("Average bill", "", "", if (st.bills > 0) (st.revenue / st.bills).rupees() else "–")
        row("Dishes sold", "", "", st.items.toString())

        heading("How guests paid")
        row("Cash", "", "", st.cash.rupees())
        row("UPI", "", "", st.upi.rupees())

        if (st.daily.isNotEmpty()) {
            heading("Day by day")
            row("Date", "", "Bills", "Revenue", bold = true)
            for (d in st.daily) row(dateLabel(d.day), "", d.bills.toString(), d.revenue.rupees())
        }
        if (st.dishes.isNotEmpty()) {
            heading("Dishes (most sold first)")
            row("Dish", "Qty", "", "Revenue", bold = true)
            for (d in st.dishes.take(40)) row(d.name, d.qty.toString(), "", d.revenue.rupees())
            if (st.dishes.size > 40) text("…and ${st.dishes.size - 40} more dishes", 10f)
        }
        y += 14
        text("Revenue figures are from bills marked paid. Please verify tax figures with your accountant.", 9f)
        doc.finishPage(page)

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "sales-summary-$start-to-$end.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }
}
