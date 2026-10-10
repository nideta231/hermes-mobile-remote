package io.github.nideta231.hermesremote.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Reads a pairing QR out of a picture: a screenshot, or the PNG `pair --qr-png` wrote and a
 * chat app (Telegram) passed along. Those arrive recompressed and sometimes huge, so the
 * image is scaled down first and decoded a few ways before giving up.
 */
object QrImage {
    private const val MAX_SIDE = 1600

    fun decode(context: Context, uri: Uri): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        return try { decode(bitmap) } finally { bitmap.recycle() }
    }

    fun decode(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return decode(RGBLuminanceSource(w, h, pixels))
    }

    fun decode(source: LuminanceSource): String? {
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        )
        // Dark-theme screenshots show the code light-on-dark; the inverted pass covers those.
        for (src in listOf(source, source.invert())) {
            for (binarize in listOf(::HybridBinarizer, ::GlobalHistogramBinarizer)) {
                val text = runCatching { QRCodeReader().decode(BinaryBitmap(binarize(src)), hints).text }.getOrNull()
                if (!text.isNullOrBlank()) return text
            }
        }
        return null
    }
}
