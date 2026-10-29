package dev.coyotecage.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import dev.coyotecage.pose.PoseEngine

class CameraAnalyzer(
    private val engine: PoseEngine,
    private val onUprightSize: (Int, Int) -> Unit,
) : ImageAnalysis.Analyzer {

    // MediaPipe 要求时间戳严格递增；同时这个值会和 SystemClock.uptimeMillis() 比较，
    // 所以必须用同一个时钟，不能用 imageInfo.timestamp（那是 nanoTime 的域）。
    private var lastTimestamp = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val raw = image.toArgbBitmap()

            // 关键：自己把画面转正，再把旋转后的位图交给 MediaPipe。
            // 不能依赖 ImageProcessingOptions.setRotationDegrees()——它只影响推理输入，
            // 返回的关键点仍是「未旋转画面」的坐标系，直接画出来整体会偏 90°。
            val rotation = image.imageInfo.rotationDegrees
            val upright = if (rotation == 0) raw else raw.rotateBy(rotation)

            onUprightSize(upright.width, upright.height)

            val now = SystemClock.uptimeMillis()
            val timestamp = if (now > lastTimestamp) now else lastTimestamp + 1
            lastTimestamp = timestamp

            engine.detect(
                bitmap = upright,
                timestampMs = timestamp,
                uprightWidth = upright.width,
                uprightHeight = upright.height,
            )
        } catch (_: Throwable) {
            // 单帧失败不影响后续
        } finally {
            image.close()
        }
    }
}

/** 顺时针旋转 [degrees]（CameraX 的 rotationDegrees 就是这么定义的）。 */
private fun Bitmap.rotateBy(degrees: Int): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(degrees.toFloat())
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

/** RGBA_8888 输出格式下，把 ImageProxy 直接拷成 Bitmap（去掉行填充）。 */
fun ImageProxy.toArgbBitmap(): Bitmap {
    val plane = planes[0]
    val buffer = plane.buffer
    buffer.rewind()

    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width
    val paddedWidth = width + rowPadding / pixelStride

    val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
    padded.copyPixelsFromBuffer(buffer)

    if (paddedWidth == width) return padded

    val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
    padded.recycle()
    return cropped
}
