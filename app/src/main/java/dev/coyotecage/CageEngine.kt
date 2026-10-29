package dev.coyotecage

import android.os.SystemClock
import dev.coyotecage.dglab.DglabV4Client
import dev.coyotecage.pose.PoseFrame
import dev.coyotecage.pose.PosePoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot

data class EngineUi(
    val running: Boolean = false,
    val poseFresh: Boolean = false,
    val outsideMs: Long = 0,
    val violating: Boolean = false,
    val targetIntensity: Int = 0,
    /** 开局准备倒计时中（不判定、不输出）。 */
    val preparing: Boolean = false,
    val prepareRemainingMs: Long = 0,
    /** 姿势锁定模式：基准姿态已记录。 */
    val poseLocked: Boolean = false,
    /** 姿势锁定模式：当前与基准的最大偏差（百分比，按画面高度归一）。 */
    val deviationPercent: Float = 0f,
)

/**
 * 判定 + 惩罚循环。
 *
 * 每 60ms 采样一次最新姿态：
 * 1. 开局先走「准备倒计时」（可关），期间不判定；
 * 2. 倒计时结束后按模式判定：
 *    - 固定框 / 随机框：判定点出框即越界；
 *    - 姿势锁定：倒计时结束时把当前姿态记成基准，之后只要偏差超过容差就算越界；
 * 3. 越界后按「电击时长 + 间隔」的节奏重复输出，强度随连续越界时间递增。
 */
class CageEngine(
    private val dglab: DglabV4Client,
    private val settings: () -> CageSettings,
    private val roi: () -> NormRect,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _ui = MutableStateFlow(EngineUi())
    val ui: StateFlow<EngineUi> = _ui.asStateFlow()

    @Volatile
    private var latest: PoseFrame? = null

    private var job: Job? = null
    private var testJob: Job? = null
    private var outputChannel: Int? = null
    private var outsideMs = 0L
    private var lastShockAt = 0L
    private var wasViolating = false
    private var prepareEndsAt = 0L
    private var referencePose: List<PosePoint>? = null

    fun onPose(frame: PoseFrame) {
        latest = frame
    }

    fun start() {
        if (job?.isActive == true) return
        val config = settings()
        outsideMs = 0L
        lastShockAt = 0L
        wasViolating = false
        referencePose = null
        prepareEndsAt = clock() + config.prepareSec.coerceIn(0, 30) * 1000L
        testJob?.cancel()
        testJob = null

        val preparing = prepareEndsAt > clock()
        _ui.update {
            it.copy(
                running = true,
                outsideMs = 0,
                violating = false,
                targetIntensity = 0,
                preparing = preparing,
                prepareRemainingMs = if (preparing) prepareEndsAt - clock() else 0,
                poseLocked = false,
                deviationPercent = 0f,
            )
        }

        job = scope.launch {
            release()
            loop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        testJob?.cancel()
        testJob = null
        scope.launch { release() }
        _ui.update {
            it.copy(
                running = false,
                outsideMs = 0,
                violating = false,
                targetIntensity = 0,
                preparing = false,
                prepareRemainingMs = 0,
                poseLocked = false,
                deviationPercent = 0f,
            )
        }
    }

    /** 用当前基础强度打一次，用来标定体感。 */
    fun testOnce() {
        if (job?.isActive == true || testJob?.isActive == true) return
        val config = settings()
        val frames = config.framesForShock() ?: return
        testJob = scope.launch {
            release()
            try {
                outputChannel = config.channel
                dglab.setIntensity(config.safeBase, config.channel)
                currentCoroutineContext().ensureActive()
                dglab.pulse(frames, config.shockMs, config.channel)
                delay(config.shockMs.toLong() + 150L)
                release()
            } catch (cancelled: CancellationException) {
                // stop/start/emergency 负责清理，旧任务不能在新一轮输出后再归零。
                throw cancelled
            } catch (_: Exception) {
                release()
            }
        }
    }

    /** 急停：立刻取消判定、清空波形、强度归零。 */
    fun emergency() {
        stop()
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val now = clock()
            val config = settings()
            val frame = latest

            if (outputChannel != null && outputChannel != config.channel) release()

            val fresh = frame != null && (now - frame.timestampMs) in 0..POSE_TIMEOUT_MS

            // 准备倒计时：只更新界面，不判定
            val remaining = prepareEndsAt - now
            if (remaining > 0) {
                outsideMs = 0L
                wasViolating = false
                _ui.update {
                    it.copy(
                        preparing = true,
                        prepareRemainingMs = remaining,
                        poseFresh = fresh,
                        outsideMs = 0,
                        violating = false,
                        targetIntensity = 0,
                    )
                }
                delay(TICK_MS)
                continue
            }

            var violating = false
            var deviation = 0f

            when (config.frameMode) {
                FrameMode.POSE -> {
                    val reference = referencePose
                    when {
                        reference == null -> {
                            // 倒计时结束，抓第一帧可用姿态当基准
                            if (fresh && frame != null && hasUsablePose(frame, config)) {
                                referencePose = frame.points
                            }
                            outsideMs = 0L
                        }

                        fresh && frame != null -> {
                            deviation = maxDeviation(reference, frame, config)
                            val over = deviation >= 0f &&
                                deviation > config.poseTolerance.coerceIn(1, 50) / 100f
                            outsideMs = if (over) outsideMs + TICK_MS else 0L
                            violating = over
                        }

                        else -> outsideMs = 0L
                    }
                }

                else -> {
                    val outside = fresh && frame != null && isOutside(frame, config)
                    outsideMs = if (outside) outsideMs + TICK_MS else 0L
                    // 随机框模式换了框就要立刻进去，没有容忍时间
                    val grace = if (config.frameMode == FrameMode.RANDOM) 0L else config.graceMs.toLong()
                    violating = outside && outsideMs >= grace
                }
            }

            val target = config.targetFor(outsideMs)

            if (violating && now - lastShockAt >= config.shockMs + config.gapMs) {
                lastShockAt = now
                fire(config, target)
            } else if (!violating && wasViolating) {
                release()
            }
            wasViolating = violating

            _ui.update {
                it.copy(
                    preparing = false,
                    prepareRemainingMs = 0,
                    poseFresh = fresh,
                    outsideMs = outsideMs,
                    violating = violating,
                    targetIntensity = target,
                    poseLocked = config.frameMode == FrameMode.POSE && referencePose != null,
                    deviationPercent = if (config.frameMode == FrameMode.POSE) deviation * 100f else 0f,
                )
            }

            delay(TICK_MS)
        }
    }

    private suspend fun fire(config: CageSettings, target: Int) {
        val frames = config.framesForShock() ?: return
        try {
            outputChannel = config.channel
            currentCoroutineContext().ensureActive()
            dglab.setIntensity(target, config.channel)
            currentCoroutineContext().ensureActive()
            dglab.pulse(frames, config.shockMs, config.channel)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            release()
        }
    }

    private suspend fun release() {
        outputChannel = null
        dglab.emergencyStop()
    }

    private fun isOutside(frame: PoseFrame, config: CageSettings): Boolean {
        if (frame.points.isEmpty()) return false
        val rect = roi()
        for (index in indicesFor(config.pointSet)) {
            val point = frame.point(index) ?: continue
            if (point.visibility < VISIBILITY_MIN) continue
            if (point.x !in 0f..1f || point.y !in 0f..1f) continue
            if (!rect.contains(point.x, point.y)) return true
        }
        return false
    }

    /**
     * 当前姿态与基准姿态的最大偏差，按画面高度归一（0.03 = 画面高度的 3%）。
     * 可比对点不足 2 个时返回 -1，表示这一帧不可信、不参与判定。
     */
    private fun maxDeviation(reference: List<PosePoint>, current: PoseFrame, config: CageSettings): Float {
        val width = current.imageWidth.coerceAtLeast(1).toFloat()
        val height = current.imageHeight.coerceAtLeast(1).toFloat()
        var max = -1f
        var compared = 0
        for (index in indicesFor(config.pointSet)) {
            val base = reference.getOrNull(index) ?: continue
            val now = current.points.getOrNull(index) ?: continue
            if (base.visibility < VISIBILITY_MIN || now.visibility < VISIBILITY_MIN) continue
            val dx = (base.x - now.x) * width
            val dy = (base.y - now.y) * height
            val distance = hypot(dx, dy) / height
            compared++
            if (distance > max) max = distance
        }
        return if (compared >= 2) max else -1f
    }

    private fun hasUsablePose(frame: PoseFrame, config: CageSettings): Boolean {
        if (frame.points.size < 33) return false
        var visible = 0
        for (index in indicesFor(config.pointSet)) {
            val point = frame.points.getOrNull(index) ?: continue
            if (point.visibility >= VISIBILITY_MIN) visible++
        }
        return visible >= 2
    }

    private fun indicesFor(pointSet: PointSet): IntArray = when (pointSet) {
        PointSet.TORSO -> TORSO_POINTS
        PointSet.FULL -> FULL_POINTS
        PointSet.FEET -> FEET_POINTS
    }

    private companion object {
        const val TICK_MS = 60L
        const val POSE_TIMEOUT_MS = 900L
        const val VISIBILITY_MIN = 0.4f

        // MediaPipe BlazePose 33 点索引
        val TORSO_POINTS = intArrayOf(11, 12, 23, 24)                 // 双肩 + 双髋
        val FULL_POINTS = intArrayOf(0, 11, 12, 15, 16, 23, 24, 25, 26, 27, 28)
        val FEET_POINTS = intArrayOf(27, 28)                          // 双踝
    }
}
