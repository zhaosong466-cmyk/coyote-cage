package dev.coyotecage

import android.content.Context

/** 判定用到的人体关键点集合（MediaPipe BlazePose 33 点索引）。 */
enum class PointSet(val label: String) {
    TORSO("躯干"),
    FULL("全身"),
    FEET("双脚"),
}

/** 判定方式：手动框选、每 N 秒随机跳框，或者锁死开局那一刻的姿势。 */
enum class FrameMode(val label: String) {
    MANUAL("固定框"),
    RANDOM("随机框"),
    POSE("姿势锁定"),
}

/** 归一化坐标矩形，(0,0) 是左上角，(1,1) 是右下角，坐标系为「已转正」的相机画面。 */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    fun clamp(): NormRect = NormRect(
        left.coerceIn(0f, 1f),
        top.coerceIn(0f, 1f),
        right.coerceIn(0f, 1f),
        bottom.coerceIn(0f, 1f),
    )

    companion object {
        val DEFAULT = NormRect(0.2f, 0.12f, 0.8f, 0.88f)
    }
}

data class CageSettings(
    val baseIntensity: Int = 5,
    val maxIntensity: Int = 25,
    val step: Int = 2,
    val escalate: Boolean = true,
    val escalateEverySec: Int = 5,
    val shockMs: Int = 600,
    val gapMs: Int = 1500,
    val graceMs: Int = 400,
    val pointSet: PointSet = PointSet.TORSO,
    val frameMode: FrameMode = FrameMode.MANUAL,
    val randomEverySec: Int = 15,
    /** 开局准备倒计时，0 = 关闭。 */
    val prepareSec: Int = 3,
    /** 姿势锁定模式的容差，单位是画面高度的百分比。 */
    val poseTolerance: Int = 3,
    val channel: Int = 0,
    val waveform: String = CageSettings.PRESET_TAP,
    val relayUrl: String = CageSettings.DEFAULT_RELAY,
    /** 调试模式：不连设备，只把要下发的参数记录下来。 */
    val debugMode: Boolean = false,
) {
    val safeMax: Int get() = maxIntensity.coerceIn(1, 100)
    val safeBase: Int get() = baseIntensity.coerceIn(1, safeMax)

    /** 连续超出 [outsideMs] 毫秒时应该加到多少强度。 */
    fun targetFor(outsideMs: Long): Int {
        if (!escalate) return safeBase
        val steps = (outsideMs / 1000L).toInt() / escalateEverySec.coerceAtLeast(1)
        return (safeBase + steps * step).coerceAtMost(safeMax)
    }

    /** 把波形模板铺满整个电击时长，一帧 100ms。 */
    fun framesForShock(): List<String>? {
        val pattern = parseFrames(waveform) ?: return null
        val count = (shockMs / 100).coerceAtLeast(1)
        return List(count) { pattern[it % pattern.size] }
    }

    companion object {
        const val DEFAULT_RELAY = "wss://trex.dungeon-lab.cn/v4"

        /** 0A=频率10，32=强度50（0x32）。一帧 8 字节 = 4 组 25ms。 */
        const val PRESET_TAP = "0A0A0A0A32323232\n0A0A0A0A00000000"
        const val PRESET_STING = "0A0A0A0A64646464\n0A0A0A0A00000000"
        const val PRESET_WAVE = "0A0A0A0A1E1E1E1E\n0A0A0A0A3C3C3C3C\n0A0A0A0A5A5A5A5A\n0A0A0A0A3C3C3C3C"

        val presets: List<Pair<String, String>> = listOf(
            "轻叩" to PRESET_TAP,
            "针刺" to PRESET_STING,
            "起伏" to PRESET_WAVE,
        )

        /** 每行 16 个十六进制字符（8 字节），非法返回 null。 */
        fun parseFrames(text: String): List<String>? {
            val lines = text.split('\n', ',', ';', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lines.isEmpty()) return null
            for (line in lines) {
                if (line.length != 16) return null
                if (!line.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            }
            return lines.map { it.uppercase() }
        }
    }
}

class SettingsStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("cage_settings", Context.MODE_PRIVATE)

    fun load(): CageSettings = CageSettings(
        baseIntensity = prefs.getInt(KEY_BASE, 5),
        maxIntensity = prefs.getInt(KEY_MAX, 25),
        step = prefs.getInt(KEY_STEP, 2),
        escalate = prefs.getBoolean(KEY_ESCALATE, true),
        escalateEverySec = prefs.getInt(KEY_ESCALATE_EVERY, 5),
        shockMs = prefs.getInt(KEY_SHOCK_MS, 600),
        gapMs = prefs.getInt(KEY_GAP_MS, 1500),
        graceMs = prefs.getInt(KEY_GRACE_MS, 400),
        pointSet = runCatching {
            PointSet.valueOf(prefs.getString(KEY_POINT_SET, null) ?: PointSet.TORSO.name)
        }.getOrDefault(PointSet.TORSO),
        frameMode = runCatching {
            FrameMode.valueOf(prefs.getString(KEY_FRAME_MODE, null) ?: FrameMode.MANUAL.name)
        }.getOrDefault(FrameMode.MANUAL),
        randomEverySec = prefs.getInt(KEY_RANDOM_EVERY, 15),
        prepareSec = prefs.getInt(KEY_PREPARE_SEC, 3),
        poseTolerance = prefs.getInt(KEY_POSE_TOLERANCE, 3),
        channel = prefs.getInt(KEY_CHANNEL, 0),
        waveform = prefs.getString(KEY_WAVEFORM, null) ?: CageSettings.PRESET_TAP,
        relayUrl = prefs.getString(KEY_RELAY, null) ?: CageSettings.DEFAULT_RELAY,
        debugMode = prefs.getBoolean(KEY_DEBUG, false),
    )

    fun save(s: CageSettings) {
        prefs.edit()
            .putInt(KEY_BASE, s.baseIntensity)
            .putInt(KEY_MAX, s.maxIntensity)
            .putInt(KEY_STEP, s.step)
            .putBoolean(KEY_ESCALATE, s.escalate)
            .putInt(KEY_ESCALATE_EVERY, s.escalateEverySec)
            .putInt(KEY_SHOCK_MS, s.shockMs)
            .putInt(KEY_GAP_MS, s.gapMs)
            .putInt(KEY_GRACE_MS, s.graceMs)
            .putString(KEY_POINT_SET, s.pointSet.name)
            .putString(KEY_FRAME_MODE, s.frameMode.name)
            .putInt(KEY_RANDOM_EVERY, s.randomEverySec)
            .putInt(KEY_PREPARE_SEC, s.prepareSec)
            .putInt(KEY_POSE_TOLERANCE, s.poseTolerance)
            .putInt(KEY_CHANNEL, s.channel)
            .putString(KEY_WAVEFORM, s.waveform)
            .putString(KEY_RELAY, s.relayUrl)
            .putBoolean(KEY_DEBUG, s.debugMode)
            .apply()
    }

    fun loadRoi(): NormRect = NormRect(
        prefs.getFloat(KEY_ROI_L, NormRect.DEFAULT.left),
        prefs.getFloat(KEY_ROI_T, NormRect.DEFAULT.top),
        prefs.getFloat(KEY_ROI_R, NormRect.DEFAULT.right),
        prefs.getFloat(KEY_ROI_B, NormRect.DEFAULT.bottom),
    ).clamp()

    fun saveRoi(r: NormRect) {
        prefs.edit()
            .putFloat(KEY_ROI_L, r.left)
            .putFloat(KEY_ROI_T, r.top)
            .putFloat(KEY_ROI_R, r.right)
            .putFloat(KEY_ROI_B, r.bottom)
            .apply()
    }

    private companion object {
        const val KEY_BASE = "base"
        const val KEY_MAX = "max"
        const val KEY_STEP = "step"
        const val KEY_ESCALATE = "escalate"
        const val KEY_ESCALATE_EVERY = "escalate_every"
        const val KEY_SHOCK_MS = "shock_ms"
        const val KEY_GAP_MS = "gap_ms"
        const val KEY_GRACE_MS = "grace_ms"
        const val KEY_POINT_SET = "point_set"
        const val KEY_FRAME_MODE = "frame_mode"
        const val KEY_RANDOM_EVERY = "random_every"
        const val KEY_PREPARE_SEC = "prepare_sec"
        const val KEY_POSE_TOLERANCE = "pose_tolerance"
        const val KEY_CHANNEL = "channel"
        const val KEY_WAVEFORM = "waveform"
        const val KEY_RELAY = "relay"
        const val KEY_DEBUG = "debug_mode"
        const val KEY_ROI_L = "roi_l"
        const val KEY_ROI_T = "roi_t"
        const val KEY_ROI_R = "roi_r"
        const val KEY_ROI_B = "roi_b"
    }
}
