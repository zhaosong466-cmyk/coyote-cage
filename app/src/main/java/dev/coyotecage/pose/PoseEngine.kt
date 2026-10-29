package dev.coyotecage.pose

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

data class PosePoint(val x: Float, val y: Float, val visibility: Float)

data class PoseFrame(
    val points: List<PosePoint>,
    val timestampMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    fun point(index: Int): PosePoint? = points.getOrNull(index)
}

/**
 * MediaPipe Pose Landmarker（lite，5.7MB）封装。
 * 优先 GPU，失败自动回落到 CPU；LIVE_STREAM 模式下丢帧而不是排队。
 */
class PoseEngine(
    private val context: Context,
    private val onFrame: (PoseFrame) -> Unit,
) {

    private var landmarker: PoseLandmarker? = null

    @Volatile private var closed = false
    @Volatile private var lastTs = 0L
    @Volatile private var lastW = 0
    @Volatile private var lastH = 0

    var usingGpu: Boolean = false
        private set

    init {
        val gpu = create(gpu = true)
        if (gpu != null) {
            landmarker = gpu
            usingGpu = true
        } else {
            landmarker = create(gpu = false)
            usingGpu = false
        }
    }

    private fun create(gpu: Boolean): PoseLandmarker? = try {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(if (gpu) Delegate.GPU else Delegate.CPU)
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> handleResult(result) }
            .setErrorListener { }
            .build()

        PoseLandmarker.createFromOptions(context, options)
    } catch (_: Throwable) {
        null
    }

    private fun handleResult(result: PoseLandmarkerResult) {
        if (closed) return
        val points = result.landmarks().firstOrNull()?.map { landmark ->
            PosePoint(landmark.x(), landmark.y(), landmark.visibility().orElse(0f))
        } ?: emptyList()
        onFrame(PoseFrame(points, lastTs, lastW, lastH))
    }

    /**
     * 传入的 [bitmap] 必须已经是「转正」的画面（调用方负责旋转），
     * 这样关键点坐标就等于屏幕预览的坐标系，不需要再做任何角度补偿。
     */
    fun detect(bitmap: Bitmap, timestampMs: Long, uprightWidth: Int, uprightHeight: Int) {
        val engine = landmarker ?: return
        if (closed) return
        lastTs = timestampMs
        lastW = uprightWidth
        lastH = uprightHeight
        try {
            val image = BitmapImageBuilder(bitmap).build()
            val processing = ImageProcessingOptions.builder().build()
            engine.detectAsync(image, processing, timestampMs)
        } catch (_: Throwable) {
            // 上一帧还没处理完时可能抛异常，直接丢掉这一帧。
        }
    }

    fun close() {
        closed = true
        runCatching { landmarker?.close() }
        landmarker = null
    }

    companion object {
        const val MODEL_ASSET = "pose_landmarker_lite.task"
    }
}
