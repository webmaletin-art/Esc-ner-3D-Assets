package app.universal3d.inspector.work

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.universal3d.core.recon.SilhouetteExtractor
import app.universal3d.core.recon.SilhouetteView
import java.nio.ByteBuffer

object ImageDecode {
    /** Decodifica la imagen con submuestreo para no agotar memoria. */
    fun decode(resolver: ContentResolver, uri: Uri, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) throw IllegalStateException("No se pudo leer la imagen (formato no soportado).")
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: throw IllegalStateException("No se pudo decodificar la imagen.")
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        return if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== bmp) bmp.recycle() } else bmp
    }

    fun rgba(bmp: Bitmap): ByteArray {
        val bb = ByteBuffer.allocate(bmp.byteCount)
        bmp.copyPixelsToBuffer(bb)
        return bb.array()
    }

    fun silhouette(resolver: ContentResolver, uri: Uri, maxSide: Int, angleRad: Float): SilhouetteView {
        val bmp = decode(resolver, uri, maxSide)
        try {
            // copyPixelsToBuffer entrega RGBA (ARGB_8888 en memoria nativa)
            return SilhouetteExtractor.fromRgba(bmp.width, bmp.height, rgba(bmp), angleRad)
        } finally { bmp.recycle() }
    }
}
