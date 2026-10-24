package com.xcloak.qrdine.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.xcloak.qrdine.data.QrItem
import java.io.File

/** A4 PDF, 4 table cards per page with cut guides. Pure local rendering. */
object QrSheet {
    private const val W = 595
    private const val H = 842

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    fun build(context: Context, restaurant: String, items: List<QrItem>): File {
        val doc = PdfDocument()
        val margin = 36f
        val cellW = (W - 2 * margin) / 2
        val cellH = (H - 2 * margin) / 2
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val guide = Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 1f; pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f) }
        val centered = Paint.Align.CENTER

        items.chunked(4).forEachIndexed { pageIndex, chunk ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageIndex + 1).create())
            val c: Canvas = page.canvas
            chunk.forEachIndexed { i, item ->
                val left = margin + (i % 2) * cellW
                val top = margin + (i / 2) * cellH
                c.drawRect(left, top, left + cellW, top + cellH, guide)
                val cx = left + cellW / 2

                ink.textAlign = centered
                ink.typeface = Typeface.DEFAULT_BOLD
                ink.textSize = 15f
                c.drawText(restaurant.take(34), cx, top + 34, ink)

                ink.textSize = 40f
                c.drawText("Table ${item.label}", cx, top + 84, ink)

                val qr = qrBitmap(item.url, 640)
                val box = 190f
                c.drawBitmap(qr, null, RectF(cx - box / 2, top + 100, cx + box / 2, top + 100 + box), null)

                ink.typeface = Typeface.DEFAULT
                ink.textSize = 12f
                c.drawText("Scan to see the menu and order", cx, top + 100 + box + 22, ink)
                ink.textSize = 10f
                c.drawText("Please tell the staff if the table is not open yet", cx, top + 100 + box + 38, ink)
            }
            doc.finishPage(page)
        }
        val dir = File(context.cacheDir, "qr").apply { mkdirs() }
        val file = File(dir, "qr-sheet.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    fun share(context: Context, file: File, title: String = "Share or print", mime: String = "application/pdf") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
