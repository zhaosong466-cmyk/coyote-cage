package dev.coyotecage

import android.os.SystemClock
import dev.coyotecage.dglab.DglabV4Client
import dev.coyotecage.pose.PoseFrame
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

data class EngineUi(
    val running: Boolean = false,
    val poseFresh: Boolean = false,
    val outsideMs: Long = 0,
    val violating: Boolean = false,
    val targetIntensity: Int = 0,
)

/**
 * 判定 + 惩罚循环。
 *
 * 每 60ms 采样一次最新姿态：越界累计时间超过 grace 就进入「越界中」，
 * 之后按「电击时长 + 间隔」的节奏重复输出，强度随连续越界时间递增。
 */
class CageEngine(
    private val dglab: DglabV4Client,
    private val settings: () -> CageSettings,
    private val roi: () -> NormRect,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {

    // UI 操作与输出在主线程串行执行；姿态推理仍在相机线程运行。
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
    private var startedAt = 0L

    fun onPose(frame: PoseFrame) {
        latest = frame
    }

    fun start() {
        if (job?.isActive == true) return
        outsideMs = 0L
        lastShockAt = 0L
        wasViolating = false
        startedAt = clock()
        testJob?.cancel()
        testJob = null
        _ui.update { it.copy(running = true, outsideMs = 0, violating = false, targetIntensity = 0) }

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
        _ui.update { it.copy(running = false, outsideMs = 0, violating = false, targetIntensity = 0) }
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
            val outside = fresh && frame != null && isOutside(frame, config)

            outsideMs = if (outside) outsideMs + TICK_MS else 0L
            // 随机框模式：换了框之后没缩进去就是立刻惩罚，没有容忍时间。
            // 只给「刚开始」留 3 秒准备时间，避免一按开始就被电。
            val warmingUp = config.frameMode == FrameMode.RANDOM &&
                now - startedAt < RANDOM_WARMUP_MS
            val grace = if (config.frameMode == FrameMode.RANDOM) 0L else config.graceMs.toLong()
            val violating = outside && !warmingUp && outsideMs >= grace
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
                    poseFresh = fresh,
                    outsideMs = outsideMs,
                    violating = violating,
                    targetIntensity = target,
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
        val indices = when (config.pointSet) {
            PointSet.TORSO -> TORSO_POINTS
            PointSet.FULL -> FULL_POINTS
            PointSet.FEET -> FEET_POINTS
        }
        for (index in indices) {
            val point = frame.point(index) ?: continue
            if (point.visibility < VISIBILITY_MIN) continue
            if (point.x !in 0f..1f || point.y !in 0f..1f) continue
            if (!rect.contains(point.x, point.y)) return true
        }
        return false
    }

    private companion object {
        const val TICK_MS = 60L
        const val POSE_TIMEOUT_MS = 900L
        const val VISIBILITY_MIN = 0.4f
        const val RANDOM_WARMUP_MS = 3000L

        // MediaPipe BlazePose 33 点索引
        val TORSO_POINTS = intArrayOf(11, 12, 23, 24)                 // 双肩 + 双髋
        val FULL_POINTS = intArrayOf(0, 11, 12, 15, 16, 23, 24, 25, 26, 27, 28)
        val FEET_POINTS = intArrayOf(27, 28)                          // 双踝
    }
}
