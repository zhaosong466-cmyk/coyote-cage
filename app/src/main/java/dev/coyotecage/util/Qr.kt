package dev.coyotecage.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

object Qr {

    /** 生成二维码位图，尺寸固定 [size]×[size]。 */
    fun encode(text: String, size: Int = 720): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        var i = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[i++] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }
}
