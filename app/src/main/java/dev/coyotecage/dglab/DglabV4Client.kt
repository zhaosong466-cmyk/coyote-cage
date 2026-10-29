package dev.coyotecage.dglab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class LinkPhase { IDLE, CONNECTING, WAITING_APP, PAIRED, ERROR }

data class DeviceInfo(val slotId: String, val name: String, val type: String)

data class LinkState(
    val phase: LinkPhase = LinkPhase.IDLE,
    /** 本机作为「控制方」的 ID，DG-LAB APP 扫码后用它接入。 */
    val controllerId: String? = null,
    /** DG-LAB APP 那一端的连接 ID。 */
    val appClientId: String? = null,
    val devices: List<DeviceInfo> = emptyList(),
    val intensityA: Int = 0,
    val intensityB: Int = 0,
    val battery: Int? = null,
    val message: String? = null,
)

/** 调试模式下记录的一条输出。 */
data class DebugEntry(val timeMs: Long, val text: String)

/**
 * DG-LAB 4 Socket（V4 协议）控制端。
 *
 * 拓扑：本机 ──ws──> 官方中继 ──ws──> DG-LAB 4 APP ──蓝牙──> 郊狼主机
 * 本机是「控制方」，APP 用 targetId 接进来，之后所有指令都走 device.op。
 */
class DglabV4Client {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(LinkState())
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _debugLog = MutableStateFlow<List<DebugEntry>>(emptyList())
    val debugLog: StateFlow<List<DebugEntry>> = _debugLog.asStateFlow()

    @Volatile
    private var debugMode = false

    val isDebugMode: Boolean get() = debugMode

    private var http: OkHttpClient? = null
    private var socket: WebSocket? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val opLock = Mutex()

    // ---------------------------------------------------------------- 连接管理

    fun connect(url: String = DEFAULT_RELAY) {
        disconnect()
        _state.value = LinkState(phase = LinkPhase.CONNECTING)

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        http = client

        try {
            val request = Request.Builder().url(url.trim()).build()
            socket = client.newWebSocket(request, listener)
        } catch (t: Throwable) {
            _state.value = LinkState(phase = LinkPhase.ERROR, message = t.message ?: "地址无效")
        }
    }

    fun disconnect() {
        val old = socket
        socket = null
        runCatching { old?.close(1000, "bye") }
        runCatching { http?.dispatcher?.executorService?.shutdown() }
        http = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        debugMode = false
        _debugLog.value = emptyList()
        _state.value = LinkState()
    }

    /**
     * 调试模式：**不建立任何网络连接**，但对外状态和正常连接完全一样
     * （phase = PAIRED、有一个假设备），所以判定、惩罚、急停走的是同一条代码路径，
     * 只是 `device.op` 不再发出去，而是记到 [debugLog]。
     */
    fun connectDebug() {
        disconnect()
        debugMode = true
        _debugLog.value = emptyList()
        _state.value = LinkState(
            phase = LinkPhase.PAIRED,
            controllerId = DEBUG_CONTROLLER_ID,
            appClientId = DEBUG_APP_ID,
            devices = listOf(DeviceInfo(DEBUG_SLOT_ID, "调试设备", "COYOTE_030")),
            battery = 100,
        )
        appendDebug("调试模式已启用：不连接设备，只记录输出")
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _state.update { it.copy(phase = LinkPhase.CONNECTING, message = null) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleFrame(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _state.update {
                it.copy(
                    phase = LinkPhase.ERROR,
                    appClientId = null,
                    message = t.message ?: "连接失败",
                )
            }
            pending.values.forEach { p -> p.cancel() }
            pending.clear()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _state.update {
                it.copy(phase = LinkPhase.IDLE, appClientId = null, devices = emptyList())
            }
        }
    }

    // ---------------------------------------------------------------- 帧处理

    private fun handleFrame(text: String) {
        val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (frame.optString("type")) {
            "hello" -> {
                val cid = frame.optString("clientId")
                _state.update {
                    it.copy(controllerId = cid, phase = LinkPhase.WAITING_APP, message = null)
                }
            }

            "client_attached" -> {
                val cid = frame.optString("clientId")
                _state.update { it.copy(appClientId = cid, phase = LinkPhase.PAIRED) }
                scope.launch { runCatching { refreshDevices() } }
            }

            "client_disconnected" -> {
                _state.update {
                    it.copy(
                        appClientId = null,
                        devices = emptyList(),
                        phase = if (it.controllerId != null) LinkPhase.WAITING_APP else LinkPhase.IDLE,
                    )
                }
            }

            "idle_timeout" -> {
                _state.update {
                    it.copy(phase = LinkPhase.ERROR, message = "5 分钟内没有 APP 接入，已断开")
                }
            }

            "error" -> {
                _state.update { it.copy(message = frame.optString("error")) }
            }

            "heartbeat" -> Unit

            "message" -> handleAppMessage(frame.optJSONObject("data"))
        }
    }

    private fun handleAppMessage(data: JSONObject?) {
        if (data == null) return
        when (data.optString("t")) {
            "resp" -> pending.remove(data.optString("reqId"))?.complete(data)

            "ev" -> when (data.optString("ev")) {
                "devices.snapshot" -> applyDevices(data.optJSONArray("devices"))
                "devices.patch" -> applyDevicePatch(data)
                "slots.patch" -> applySlots(data.optJSONArray("slots"))
                else -> Unit
            }

            else -> Unit
        }
    }

    private fun applyDevices(devices: JSONArray?) {
        if (devices == null) return
        val list = ArrayList<DeviceInfo>(devices.length())
        for (i in 0 until devices.length()) {
            val obj = devices.optJSONObject(i) ?: continue
            list.add(
                DeviceInfo(
                    slotId = obj.optString("slotId"),
                    name = obj.optString("name"),
                    type = obj.optString("type"),
                )
            )
            obj.optJSONObject("props")?.let { applyProps(it) }
        }
        _state.update { it.copy(devices = list) }
    }

    private fun applyDevicePatch(data: JSONObject) {
        val added = data.optJSONArray("added")
        val removed = data.optJSONArray("removed")

        if (added != null) {
            for (i in 0 until added.length()) {
                val obj = added.optJSONObject(i) ?: continue
                _state.update { st ->
                    val merged = st.devices.filterNot { it.slotId == obj.optString("slotId") } +
                        DeviceInfo(
                            slotId = obj.optString("slotId"),
                            name = obj.optString("name"),
                            type = obj.optString("type"),
                        )
                    st.copy(devices = merged)
                }
                obj.optJSONObject("props")?.let { applyProps(it) }
            }
        }

        if (removed != null) {
            for (i in 0 until removed.length()) {
                val slot = removed.optString(i)
                _state.update { st -> st.copy(devices = st.devices.filterNot { it.slotId == slot }) }
            }
        }
    }

    private fun applySlots(slots: JSONArray?) {
        if (slots == null) return
        for (i in 0 until slots.length()) {
            slots.optJSONObject(i)?.optJSONObject("props")?.let { applyProps(it) }
        }
    }

    private fun applyProps(props: JSONObject) {
        val a = if (props.has("intensityA")) props.optInt("intensityA", -1) else -1
        val b = if (props.has("intensityB")) props.optInt("intensityB", -1) else -1
        val power = if (props.has("power")) props.optInt("power", -1) else -1
        _state.update { st ->
            st.copy(
                intensityA = if (a >= 0) a else st.intensityA,
                intensityB = if (b >= 0) b else st.intensityB,
                battery = if (power >= 0) power else st.battery,
            )
        }
    }

    // ---------------------------------------------------------------- 指令

    private fun firstSlot(): String? = _state.value.devices.firstOrNull()?.slotId

    suspend fun refreshDevices() {
        val response = rpc("devices.get", null) ?: return
        applyDevices(response.optJSONObject("result")?.optJSONArray("devices"))
    }

    /** 把某个通道的绝对强度调到 [target]（V4 只能相对增减，这里算差值）。 */
    suspend fun setIntensity(target: Int, channel: Int) {
        opLock.withLock {
            val slot = firstSlot() ?: return@withLock
            val st = _state.value
            val current = if (channel == 0) st.intensityA else st.intensityB
            val delta = target - current
            if (delta == 0) return@withLock

            // 先更新本地状态再下发，调试日志里显示的才是「下发后的强度」
            _state.update {
                if (channel == 0) it.copy(intensityA = target) else it.copy(intensityB = target)
            }
            fireDeviceOp(
                JSONObject()
                    .put("s", slot)
                    .put("t", 3) // AddIntensity
                    .put("c", channel)
                    .put("p", 1)
                    .put("v", delta)
            )
        }
    }

    /** 下发波形帧，每帧 100ms，[durationMs] 是播放上限。 */
    suspend fun pulse(frames: List<String>, durationMs: Int, channel: Int) {
        if (frames.isEmpty()) return
        opLock.withLock {
            val slot = firstSlot() ?: return@withLock
            val array = JSONArray()
            frames.forEach { array.put(it) }
            fireDeviceOp(
                JSONObject()
                    .put("s", slot)
                    .put("t", 0) // AppendPulseData
                    .put("c", channel)
                    .put("p", 1)
                    .put("d", durationMs)
                    .put("v", array)
            )
        }
    }

    /** 清空该通道已排队的波形任务。 */
    suspend fun clear(channel: Int) {
        opLock.withLock {
            val slot = firstSlot() ?: return@withLock
            fireRequest("device.op.clear", JSONObject().put("s", slot).put("c", channel))
        }
    }

    /** 把强度直接归零（急停用）。 */
    suspend fun zero(channel: Int) {
        opLock.withLock {
            val slot = firstSlot() ?: return@withLock
            _state.update {
                if (channel == 0) it.copy(intensityA = 0) else it.copy(intensityB = 0)
            }
            fireDeviceOp(
                JSONObject()
                    .put("s", slot)
                    .put("t", 7) // SetIntensity 0
                    .put("c", channel)
                    .put("p", 1)
                    .put("v", 0)
            )
        }
    }

    suspend fun emergencyStop() {
        opLock.withLock {
            val slot = firstSlot() ?: return@withLock
            // 先归零，再清队列；不等待 RPC 回复，也不让普通输出插入这三条指令之间。
            fireDeviceOp(
                JSONObject().put("s", slot).put("t", 7).put("c", 0).put("p", 1).put("v", 0)
            )
            fireDeviceOp(
                JSONObject().put("s", slot).put("t", 7).put("c", 1).put("p", 1).put("v", 0)
            )
            fireRequest("device.op.clear", JSONObject().put("s", slot))
            _state.update { it.copy(intensityA = 0, intensityB = 0) }
        }
    }

    // ---------------------------------------------------------------- 底层发送

    private suspend fun rpc(method: String, params: JSONObject?): JSONObject? {
        if (debugMode) {
            val channel = params?.optInt("c", -1) ?: -1
            appendDebug(
                if (method == "device.op.clear" && channel >= 0) {
                    "清空 " + (if (channel == 0) "A" else "B") + " 通道波形"
                } else {
                    "RPC $method"
                }
            )
            return JSONObject().put("result", JSONObject())
        }
        val clientId = _state.value.appClientId ?: return null
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pending[reqId] = deferred

        val payload = JSONObject()
            .put("t", "req")
            .put("reqId", reqId)
            .put("m", method)
        if (params != null) payload.put("data", params)

        val envelope = JSONObject()
            .put("type", "message")
            .put("clientId", clientId)
            .put("data", payload)

        if (socket?.send(envelope.toString()) != true) {
            pending.remove(reqId)
            return null
        }

        return try {
            withTimeout(5_000) { deferred.await() }
        } catch (_: Throwable) {
            null
        } finally {
            pending.remove(reqId)
        }
    }

    private fun fireDeviceOp(op: JSONObject) {
        fireRequest("device.op", op)
    }

    /** 输出与清队列均按 WebSocket 顺序发送，不等待设备确认。 */
    private fun fireRequest(method: String, data: JSONObject) {
        if (debugMode) {
            val channel = data.optInt("c", -1)
            appendDebug(when {
                method == "device.op" -> describe(data)
                channel >= 0 -> "清空 " + (if (channel == 0) "A" else "B") + " 通道波形"
                else -> "RPC $method"
            })
            return
        }
        val clientId = _state.value.appClientId ?: return
        val payload = JSONObject()
            .put("t", "req")
            .put("reqId", UUID.randomUUID().toString())
            .put("m", method)
            .put("data", data)
        val envelope = JSONObject()
            .put("type", "message")
            .put("clientId", clientId)
            .put("data", payload)
        socket?.send(envelope.toString())
    }

    // ---------------------------------------------------------------- 调试

    /** 把一条 device.op 翻译成人话，调试面板直接显示。 */
    private fun describe(op: JSONObject): String {
        val channel = if (op.optInt("c", 0) == 0) "A" else "B"
        return when (op.optInt("t", -1)) {
            0 -> {
                val frames = op.optJSONArray("v")
                val count = frames?.length() ?: 0
                val first = frames?.optString(0) ?: "-"
                "波形 $channel · ${op.optInt("d", 0)}ms · $count 帧 · 首帧 $first"
            }

            3 -> {
                val delta = op.optInt("v", 0)
                val current = if (op.optInt("c", 0) == 0) {
                    _state.value.intensityA
                } else {
                    _state.value.intensityB
                }
                val sign = if (delta >= 0) "+$delta" else "$delta"
                "强度 $channel $sign → $current"
            }

            7 -> "归零 $channel"

            else -> "op t=" + op.optInt("t", -1)
        }
    }

    private fun appendDebug(text: String) {
        val entry = DebugEntry(System.currentTimeMillis(), text)
        _debugLog.update { (it + entry).takeLast(120) }
    }

    // ---------------------------------------------------------------- 配对

    /** APP 端要连接的地址（自建服务端时换成自己的域名）。 */
    fun pairUrl(relayUrl: String): String? {
        val tid = _state.value.controllerId ?: return null
        return buildPairUrl(relayUrl, tid)
    }

    /** 官方「扫码打开 APP」的包装链接。 */
    fun pairingQrContent(relayUrl: String): String? {
        val pair = pairUrl(relayUrl) ?: return null
        val encoded = URLEncoder.encode(pair, "UTF-8")
        return "$QR_PREFIX$encoded"
    }

    companion object {
        const val DEFAULT_RELAY = "wss://trex.dungeon-lab.cn/v4"
        const val QR_PREFIX = "https://dungeon-lab.cn/s/?v=1&action=socket&url="

        private const val DEBUG_CONTROLLER_ID = "debug-controller"
        private const val DEBUG_APP_ID = "debug-app"
        private const val DEBUG_SLOT_ID = "debug-slot"
    }
}

/**
 * V4 的配对地址：`<中继地址>?tid=<控制方 ID>`。
 * 官方文档里是 `wss://trex.dungeon-lab.cn/v4?tid=xxx`，不要多加斜杠。
 */
fun buildPairUrl(relayUrl: String, controllerId: String): String {
    val base = relayUrl.trim().trimEnd('/')
    return if (base.contains('?')) "$base&tid=$controllerId" else "$base?tid=$controllerId"
}
