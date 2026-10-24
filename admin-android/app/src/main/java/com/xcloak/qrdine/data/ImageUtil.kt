package com.xcloak.qrdine.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

object ImageUtil {
    private const val MAX_BYTES = 900_000   // bucket limit is 1 MB
    private const val MAX_SIDE = 1024

    /** Decode, downscale to 1024px max and JPEG-compress under the bucket limit. Call off the main thread. */
    fun compress(context: Context, uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > MAX_SIDE * 2 || bounds.outHeight / sample > MAX_SIDE * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Could not read image")
        val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val bmp = if (scale < 1f)
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        else decoded
        var quality = 85
        var out: ByteArray
        do {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            out = bos.toByteArray()
            quality -= 10
        } while (out.size > MAX_BYTES && quality >= 35)
        return out
    }
}
