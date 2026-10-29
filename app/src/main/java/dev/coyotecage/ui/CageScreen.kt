package dev.coyotecage.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.coyotecage.CageEngine
import dev.coyotecage.CageSettings
import dev.coyotecage.EngineUi
import dev.coyotecage.FrameMode
import dev.coyotecage.NormRect
import dev.coyotecage.SettingsStore
import dev.coyotecage.camera.CameraAnalyzer
import dev.coyotecage.dglab.DebugEntry
import dev.coyotecage.dglab.DglabV4Client
import dev.coyotecage.dglab.LinkPhase
import dev.coyotecage.dglab.LinkState
import dev.coyotecage.pose.PoseEngine
import dev.coyotecage.pose.PoseFrame
import dev.coyotecage.pose.PosePoint
import dev.coyotecage.pose.PoseSkeleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

@Composable
fun CageApp() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }

    var settings by remember { mutableStateOf(store.load()) }
    var roi by remember { mutableStateOf(store.loadRoi()) }

    val dglab = remember { DglabV4Client() }
    val link by dglab.state.collectAsState()

    val engine = remember { CageEngine(dglab, { settings }, { roi }) }
    val engineUi by engine.ui.collectAsState()
    val debugLog by dglab.debugLog.collectAsState()

    val poseFlow = remember { MutableStateFlow<PoseFrame?>(null) }
    val poseEngine = remember {
        PoseEngine(context) { frame ->
            engine.onPose(frame)
            poseFlow.value = frame
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            engine.emergency()
            poseEngine.close()
            dglab.disconnect()
        }
    }

    var showSettings by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var imageWidth by remember { mutableStateOf(0) }
    var imageHeight by remember { mutableStateOf(0) }

    val analyzer = remember {
        CameraAnalyzer(poseEngine) { w, h ->
            imageWidth = w
            imageHeight = h
        }
    }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result -> granted = result }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(settings) {
        delay(350)
        store.save(settings)
    }
    LaunchedEffect(link.phase) {
        if (link.phase != LinkPhase.PAIRED) engine.stop()
    }
    LaunchedEffect(settings.debugMode) {
        // 开关调试模式时，把已有的连接（真实的或调试的）断掉，避免状态串味
        if (settings.debugMode) {
            if (!dglab.isDebugMode) dglab.disconnect()
        } else if (dglab.isDebugMode) {
            dglab.disconnect()
        }
    }

    // 随机框模式：每 N 秒换一个框，且不和上一个框落在同一个象限
    var randomRemaining by remember { mutableStateOf(0) }
    LaunchedEffect(engineUi.running, settings.frameMode, settings.randomEverySec) {
        if (!engineUi.running || settings.frameMode != FrameMode.RANDOM) {
            randomRemaining = 0
            return@LaunchedEffect
        }
        val random = Random(System.nanoTime())
        var lastQuadrant = quadrantOf(roi)
        while (isActive) {
            val next = randomRect(lastQuadrant, random)
            lastQuadrant = next.quadrant
            roi = next.rect
            for (second in settings.randomEverySec downTo 1) {
                randomRemaining = second
                delay(1000L)
            }
        }
    }

    val randomMode = settings.frameMode == FrameMode.RANDOM
    val poseMode = settings.frameMode == FrameMode.POSE
    val prepareSeconds = if (engineUi.preparing) {
        ceil(engineUi.prepareRemainingMs / 1000.0).toInt().coerceAtLeast(1)
    } else {
        0
    }
    // 进入随机框 / 姿势锁定模式时，退出手动框选状态
    LaunchedEffect(randomMode, poseMode) {
        if (randomMode || poseMode) selecting = false
    }
    val onConnect: () -> Unit = {
        if (settings.debugMode) dglab.connectDebug() else dglab.connect(settings.relayUrl)
    }
    val onToggleRun: () -> Unit = { if (engineUi.running) engine.stop() else engine.start() }
    val onToggleSelect: () -> Unit = { if (!randomMode && !poseMode) selecting = !selecting }
    val onSwitchCamera: () -> Unit = {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding(),
    ) {
        val landscape = maxWidth > maxHeight

        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                CameraStage(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    granted = granted,
                    lensFacing = lensFacing,
                    analyzer = analyzer,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    roi = roi,
                    onRoiChange = { newRoi ->
                        roi = newRoi
                        // 只保存手画的框，随机跳出来的框不算
                        if (!randomMode) store.saveRoi(newRoi)
                    },
                    pointsFlow = poseFlow,
                    selecting = selecting && !randomMode && !poseMode,
                    violating = engineUi.violating,
                    poseWarning = !engineUi.poseFresh && engineUi.running && !engineUi.preparing,
                    poseMode = poseMode,
                    prepareSeconds = prepareSeconds,
                )
                SidePanel(
                    modifier = Modifier.width(300.dp).fillMaxHeight(),
                    link = link,
                    ui = engineUi,
                    ready = link.phase == LinkPhase.PAIRED,
                    selecting = selecting,
                    randomMode = randomMode,
                    poseMode = poseMode,
                    poseTolerance = settings.poseTolerance,
                    randomRemaining = randomRemaining,
                    debugMode = settings.debugMode,
                    debugLog = debugLog,
                    onConnect = onConnect,
                    onShowQr = { showQr = true },
                    onDisconnect = { dglab.disconnect() },
                    onToggleRun = onToggleRun,
                    onToggleSelect = onToggleSelect,
                    onSwitchCamera = onSwitchCamera,
                    onSettings = { showSettings = true },
                    onEmergency = { engine.emergency() },
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                StatusBar(
                    link = link,
                    onConnect = onConnect,
                    onShowQr = { showQr = true },
                    onDisconnect = { dglab.disconnect() },
                )
                CameraStage(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    granted = granted,
                    lensFacing = lensFacing,
                    analyzer = analyzer,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    roi = roi,
                    onRoiChange = { newRoi ->
                        roi = newRoi
                        if (!randomMode) store.saveRoi(newRoi)
                    },
                    pointsFlow = poseFlow,
                    selecting = selecting && !randomMode && !poseMode,
                    violating = engineUi.violating,
                    poseWarning = !engineUi.poseFresh && engineUi.running && !engineUi.preparing,
                    poseMode = poseMode,
                    prepareSeconds = prepareSeconds,
                )
                if (settings.debugMode) {
                    DebugPanel(entries = debugLog)
                }
                ControlBar(
                    ui = engineUi,
                    ready = link.phase == LinkPhase.PAIRED,
                    selecting = selecting,
                    randomMode = randomMode,
                    poseMode = poseMode,
                    poseTolerance = settings.poseTolerance,
                    randomRemaining = randomRemaining,
                    onToggleRun = onToggleRun,
                    onToggleSelect = onToggleSelect,
                    onSwitchCamera = onSwitchCamera,
                    onSettings = { showSettings = true },
                    onEmergency = { engine.emergency() },
                )
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            debugLog = debugLog,
            onChange = { settings = it },
            onTest = { engine.testOnce() },
            onDismiss = { showSettings = false },
        )
    }

    if (showQr) {
        QrDialog(
            link = link,
            relayUrl = settings.relayUrl,
            onDismiss = { showQr = false },
        )
    }
}

// --------------------------------------------------------------------- 画面

@Composable
private fun CameraStage(
    modifier: Modifier,
    granted: Boolean,
    lensFacing: Int,
    analyzer: CameraAnalyzer,
    imageWidth: Int,
    imageHeight: Int,
    roi: NormRect,
    onRoiChange: (NormRect) -> Unit,
    pointsFlow: StateFlow<PoseFrame?>,
    selecting: Boolean,
    violating: Boolean,
    poseWarning: Boolean,
    poseMode: Boolean,
    prepareSeconds: Int,
) {
    Box(modifier) {
        if (granted) {
            CameraLayer(
                lensFacing = lensFacing,
                analyzer = analyzer,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("需要相机权限", color = Color.White)
            }
        }

        RoiOverlay(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            mirror = lensFacing == CameraSelector.LENS_FACING_FRONT,
            roi = roi,
            onRoiChange = onRoiChange,
            pointsFlow = pointsFlow,
            selecting = selecting,
            violating = violating,
            showFrameBox = !poseMode,
        )

        if (prepareSeconds > 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = prepareSeconds.toString(),
                        color = Color.White,
                        fontSize = 84.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (poseMode) "摆好姿势" else "准备",
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                }
            }
        }

        if (selecting) {
            Text(
                text = "拖动框出判定区域",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(10.dp)
                    .background(Color(0x99000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        if (poseWarning) {
            Text(
                text = "没检测到人，已暂停判定",
                color = COLOR_ALERT,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(10.dp)
                    .background(Color(0x99000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

// --------------------------------------------------------------------- 顶栏

@Composable
private fun StatusBar(
    link: LinkState,
    onConnect: () -> Unit,
    onShowQr: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(COLOR_PANEL)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(statusLine(link), color = Color.White, fontSize = 14.sp)
            Text(
                text = statusDetail(link),
                color = COLOR_DIM,
                fontSize = 11.sp,
                maxLines = 2,
            )
        }
        when (link.phase) {
            LinkPhase.IDLE -> Button(onClick = onConnect) { Text("连接") }
            LinkPhase.ERROR -> Button(onClick = onConnect) { Text("重连") }
            LinkPhase.CONNECTING -> Text("连接中…", color = COLOR_DIM, fontSize = 12.sp)
            LinkPhase.WAITING_APP -> Button(onClick = onShowQr) { Text("二维码") }
            LinkPhase.PAIRED -> TextButton(onClick = onDisconnect) { Text("断开") }
        }
    }
}

private fun statusLine(link: LinkState): String = when (link.phase) {
    LinkPhase.IDLE -> "未连接"
    LinkPhase.CONNECTING -> "正在连接中继…"
    LinkPhase.WAITING_APP -> "等待 DG-LAB APP 扫码"
    LinkPhase.PAIRED -> "已连接 · " + (link.devices.firstOrNull()?.name ?: "等待设备")
    LinkPhase.ERROR -> "连接异常"
}

private fun statusDetail(link: LinkState): String {
    val parts = ArrayList<String>(3)
    link.devices.firstOrNull()?.let { parts.add(it.type) }
    if (link.devices.isNotEmpty()) {
        parts.add("A " + link.intensityA + " / B " + link.intensityB)
        link.battery?.let { parts.add("电量 " + it + "%") }
    }
    link.message?.let { parts.add(it) }
    if (parts.isEmpty()) parts.add("点右上角连接，再用 DG-LAB 4 APP 扫码")
    return parts.joinToString(" · ")
}

// --------------------------------------------------------------------- 控制条

@Composable
private fun ControlBar(
    ui: EngineUi,
    ready: Boolean,
    selecting: Boolean,
    randomMode: Boolean,
    poseMode: Boolean,
    poseTolerance: Int,
    randomRemaining: Int,
    onToggleRun: () -> Unit,
    onToggleSelect: () -> Unit,
    onSwitchCamera: () -> Unit,
    onSettings: () -> Unit,
    onEmergency: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(COLOR_PANEL)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = statusLabel(ui),
                color = when {
                    ui.preparing -> COLOR_OK
                    ui.violating -> COLOR_ALERT
                    ui.running -> COLOR_OK
                    else -> COLOR_DIM
                },
                fontSize = 13.sp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = meterLabel(ui, randomMode, poseMode, poseTolerance, randomRemaining),
                color = COLOR_DIM,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onToggleRun,
                enabled = ready || ui.running,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (ui.running) "停止" else "开始")
            }
            OutlinedButton(
                onClick = onToggleSelect,
                enabled = !randomMode && !poseMode,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (selecting) "完成" else "框选")
            }
            OutlinedButton(onClick = onSwitchCamera) { Text("镜头") }
            OutlinedButton(onClick = onSettings) { Text("设置") }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onEmergency,
            colors = ButtonDefaults.buttonColors(containerColor = COLOR_ALERT),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("急停（清空波形并归零）")
        }
    }
}

// --------------------------------------------------------------------- 横屏侧栏

@Composable
private fun SidePanel(
    modifier: Modifier,
    link: LinkState,
    ui: EngineUi,
    ready: Boolean,
    selecting: Boolean,
    randomMode: Boolean,
    poseMode: Boolean,
    poseTolerance: Int,
    randomRemaining: Int,
    debugMode: Boolean,
    debugLog: List<DebugEntry>,
    onConnect: () -> Unit,
    onShowQr: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleRun: () -> Unit,
    onToggleSelect: () -> Unit,
    onSwitchCamera: () -> Unit,
    onSettings: () -> Unit,
    onEmergency: () -> Unit,
) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    Column(
        modifier
            .background(COLOR_PANEL)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(statusLine(link), color = Color.White, fontSize = 13.sp, maxLines = 2)
        Text(statusDetail(link), color = COLOR_DIM, fontSize = 10.sp, maxLines = 2)

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (link.phase) {
                LinkPhase.IDLE -> Button(onClick = onConnect) { Text("连接") }
                LinkPhase.ERROR -> Button(onClick = onConnect) { Text("重连") }
                LinkPhase.CONNECTING -> Text("连接中…", color = COLOR_DIM, fontSize = 12.sp)
                LinkPhase.WAITING_APP -> Button(onClick = onShowQr) { Text("二维码") }
                LinkPhase.PAIRED -> OutlinedButton(onClick = onDisconnect) { Text("断开") }
            }
        }

        Spacer(Modifier.height(2.dp))

        Text(
            text = statusLabel(ui),
            color = when {
                ui.preparing -> COLOR_OK
                ui.violating -> COLOR_ALERT
                ui.running -> COLOR_OK
                else -> COLOR_DIM
            },
            fontSize = 13.sp,
        )
        Text(
            text = meterLabel(ui, randomMode, poseMode, poseTolerance, randomRemaining),
            color = COLOR_DIM,
            fontSize = 11.sp,
        )

        if (debugMode) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xF0101014), RoundedCornerShape(6.dp))
                    .padding(6.dp),
            ) {
                debugLog.takeLast(3).forEach { entry ->
                    Text(
                        text = formatter.format(Date(entry.timeMs)) + " " + entry.text,
                        color = Color(0xFFD8D8D8),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onToggleRun,
            enabled = ready || ui.running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (ui.running) "停止" else "开始")
        }
        OutlinedButton(
            onClick = onToggleSelect,
            enabled = !randomMode,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (selecting) "完成" else "框选")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onSwitchCamera, modifier = Modifier.weight(1f)) {
                Text("镜头")
            }
            OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f)) {
                Text("设置")
            }
        }
        Button(
            onClick = onEmergency,
            colors = ButtonDefaults.buttonColors(containerColor = COLOR_ALERT),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("急停")
        }
    }
}

// --------------------------------------------------------------------- 随机框

/** 画面四等分后，框中心落在哪个象限：0=左上 1=右上 2=左下 3=右下。 */
private fun quadrantOf(rect: NormRect): Int {
    val cx = (rect.left + rect.right) / 2f
    val cy = (rect.top + rect.bottom) / 2f
    val qx = if (cx < 0.5f) 0 else 1
    val qy = if (cy < 0.5f) 0 else 1
    return qy * 2 + qx
}

private data class RandomBox(val rect: NormRect, val quadrant: Int)

/**
 * 在 [previous] 之外的象限里生成一个随机大小的框。
 * 上半屏（象限 0/1）合计只占 [TOP_QUADRANT_CHANCE]，避免老是出现够不着的框；
 * 尺寸下限故意留得很小，有些框小到必须缩起来才塞得进去。
 */
private fun randomRect(previous: Int, random: Random): RandomBox {
    val top = random.nextFloat() < TOP_QUADRANT_CHANCE
    val base = if (top) 0 else 2
    var quadrant = base + random.nextInt(2)
    if (quadrant == previous) {
        // 同一半屏只有两个象限，换成另一个就必然满足「不和上次同区」
        quadrant = base + (quadrant - base + 1) % 2
    }

    val width = 0.16f + random.nextFloat() * (0.46f - 0.16f)
    val height = 0.18f + random.nextFloat() * (0.46f - 0.18f)
    val cellX = (quadrant % 2) * 0.5f
    val cellY = (quadrant / 2) * 0.5f

    val left = cellX + random.nextFloat() * (0.5f - width)
    val topY = cellY + random.nextFloat() * (0.5f - height)
    return RandomBox(NormRect(left, topY, left + width, topY + height), quadrant)
}

/** 上半屏出现的概率。 */
private const val TOP_QUADRANT_CHANCE = 0.10f

private fun formatSeconds(ms: Long): String = String.format("%.1f", ms / 1000f)

private fun statusLabel(ui: EngineUi): String = when {
    ui.preparing -> "准备中"
    ui.violating -> "越界中"
    ui.running -> "监守中"
    else -> "未开始"
}

private fun meterLabel(
    ui: EngineUi,
    randomMode: Boolean,
    poseMode: Boolean,
    poseTolerance: Int,
    randomRemaining: Int,
): String = buildString {
    when {
        ui.preparing -> {
            append("倒计时 ")
            append(ceil(ui.prepareRemainingMs / 1000.0).toInt().coerceAtLeast(1))
            append("s")
        }

        poseMode -> {
            append("偏差 ")
            append(String.format("%.1f", ui.deviationPercent))
            append("% / 容差 ")
            append(poseTolerance)
            append("% · 强度 ")
            append(ui.targetIntensity)
        }

        else -> {
            append("越界 ")
            append(formatSeconds(ui.outsideMs))
            append("s · 强度 ")
            append(ui.targetIntensity)
            if (randomMode && ui.running) {
                append(" · 下一个框 ")
                append(randomRemaining)
                append("s")
            }
        }
    }
}

// --------------------------------------------------------------------- 调试面板

@Composable
private fun DebugPanel(entries: List<DebugEntry>) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xF0101014))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text("调试输出 · 不连设备，仅记录", color = COLOR_OK, fontSize = 10.sp)
        val recent = entries.takeLast(4)
        if (recent.isEmpty()) {
            Text("等待输出…", color = COLOR_DIM, fontSize = 10.sp)
        } else {
            recent.forEach { entry ->
                Text(
                    text = formatter.format(Date(entry.timeMs)) + "  " + entry.text,
                    color = Color(0xFFD8D8D8),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
    }
}

// --------------------------------------------------------------------- 相机

@Composable
private fun CameraLayer(
    lensFacing: Int,
    analyzer: CameraAnalyzer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    val executor = remember { Executors.newSingleThreadExecutor() }

    val selector = remember {
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
    }

    // 分析帧压到 640×480，推理快一点，发热少一点。
    val analysisSelector = remember {
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    android.util.Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()
    }

    val preview = remember {
        Preview.Builder()
            .setResolutionSelector(selector)
            .build()
    }

    val analysis = remember {
        ImageAnalysis.Builder()
            .setResolutionSelector(analysisSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
    }

    LaunchedEffect(analyzer) {
        analysis.setAnalyzer(executor, analyzer)
    }

    LaunchedEffect(lensFacing, previewView) {
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val provider = awaitCameraProvider(context) ?: return@LaunchedEffect
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analysis)
        } catch (_: Throwable) {
            // 设备没有该摄像头等情况，保持黑屏即可
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { analysis.clearAnalyzer() }
            runCatching { executor.shutdown() }
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider? =
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<ProcessCameraProvider?> { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    continuation.resume(runCatching { future.get() }.getOrNull())
                },
                ContextCompat.getMainExecutor(context),
            )
        }
    }

// --------------------------------------------------------------------- 覆盖层

@Composable
private fun RoiOverlay(
    imageWidth: Int,
    imageHeight: Int,
    mirror: Boolean,
    roi: NormRect,
    onRoiChange: (NormRect) -> Unit,
    pointsFlow: StateFlow<PoseFrame?>,
    selecting: Boolean,
    violating: Boolean,
    showFrameBox: Boolean = true,
) {
    // 关键点每帧都在变，单独在这里订阅，避免整棵树跟着重组。
    val frame by pointsFlow.collectAsState()
    val points: List<PosePoint> = frame?.points ?: emptyList()

    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragNow by remember { mutableStateOf<Offset?>(null) }

    val overlayMap = remember(viewSize, imageWidth, imageHeight, mirror) {
        ViewMap(
            viewWidth = viewSize.width.toFloat(),
            viewHeight = viewSize.height.toFloat(),
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            mirror = mirror,
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .pointerInput(selecting, overlayMap) {
                if (!selecting) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        dragStart = offset
                        dragNow = offset
                    },
                    onDrag = { change, _ ->
                        dragNow = change.position
                        change.consume()
                    },
                    onDragEnd = {
                        val start = dragStart
                        val end = dragNow
                        if (start != null && end != null && overlayMap.valid) {
                            val a = overlayMap.toNormalized(start)
                            val b = overlayMap.toNormalized(end)
                            val rect = NormRect(
                                left = min(a.x, b.x),
                                top = min(a.y, b.y),
                                right = max(a.x, b.x),
                                bottom = max(a.y, b.y),
                            )
                            if (rect.width > 0.05f && rect.height > 0.05f) onRoiChange(rect)
                        }
                        dragStart = null
                        dragNow = null
                    },
                    onDragCancel = {
                        dragStart = null
                        dragNow = null
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (!overlayMap.valid) return@Canvas

            // 前置摄像头是镜像的，两个角的先后顺序会反过来，所以统一取 min/max。
            val cornerA = overlayMap.toView(roi.left, roi.top)
            val cornerB = overlayMap.toView(roi.right, roi.bottom)
            val boxLeft = min(cornerA.x, cornerB.x)
            val boxTop = min(cornerA.y, cornerB.y)
            val boxWidth = max(cornerA.x, cornerB.x) - boxLeft
            val boxHeight = max(cornerA.y, cornerB.y) - boxTop
            val topLeft = Offset(boxLeft, boxTop)
            val boxColor = if (violating) COLOR_ALERT else COLOR_OK

            if (showFrameBox) {
                drawRect(
                    color = boxColor.copy(alpha = 0.10f),
                    topLeft = topLeft,
                    size = Size(boxWidth, boxHeight),
                )
                drawRect(
                    color = boxColor,
                    topLeft = topLeft,
                    size = Size(boxWidth, boxHeight),
                    style = Stroke(
                        width = 4f,
                        pathEffect = if (selecting) {
                            PathEffect.dashPathEffect(floatArrayOf(24f, 16f))
                        } else {
                            null
                        },
                    ),
                )
            }

            // 先算一遍屏幕坐标，骨架和关节点共用
            val screenPoints: List<Offset?> = points.map { point ->
                if (point.visibility < 0.4f) null else overlayMap.toView(point.x, point.y)
            }

            // 骨架连线：框模式下按是否在框内变色，姿势模式下按是否越界变色
            for (connection in PoseSkeleton.CONNECTIONS) {
                val fromIndex = connection[0]
                val toIndex = connection[1]
                val fromPoint = points.getOrNull(fromIndex) ?: continue
                val toPoint = points.getOrNull(toIndex) ?: continue
                val from = screenPoints.getOrNull(fromIndex) ?: continue
                val to = screenPoints.getOrNull(toIndex) ?: continue
                val ok = if (showFrameBox) {
                    roi.contains(fromPoint.x, fromPoint.y) && roi.contains(toPoint.x, toPoint.y)
                } else {
                    !violating
                }
                drawLine(
                    color = if (ok) COLOR_OK else COLOR_ALERT,
                    start = from,
                    end = to,
                    strokeWidth = 3f,
                    cap = StrokeCap.Round,
                )
            }

            for (index in points.indices) {
                val center = screenPoints[index] ?: continue
                val point = points[index]
                val ok = if (showFrameBox) roi.contains(point.x, point.y) else !violating
                drawCircle(
                    color = if (ok) COLOR_OK else COLOR_ALERT,
                    radius = 5.5f,
                    center = center,
                )
            }

            val start = dragStart
            val end = dragNow
            if (selecting && start != null && end != null) {
                val left = min(start.x, end.x)
                val top = min(start.y, end.y)
                val right = max(start.x, end.x)
                val bottom = max(start.y, end.y)
                drawRect(
                    color = Color.White,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    style = Stroke(
                        width = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f)),
                    ),
                )
            }
        }
    }
}

/**
 * 归一化坐标 <-> 屏幕坐标。
 * PreviewView 用 FIT_CENTER，所以画面完整可见，只需要按比例居中缩放。
 */
private class ViewMap(
    viewWidth: Float,
    viewHeight: Float,
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val mirror: Boolean,
) {
    val valid: Boolean = viewWidth > 0f && viewHeight > 0f && imageWidth > 0 && imageHeight > 0

    private val scale: Float =
        if (valid) min(viewWidth / imageWidth.toFloat(), viewHeight / imageHeight.toFloat()) else 1f
    private val dx: Float = if (valid) (viewWidth - imageWidth * scale) / 2f else 0f
    private val dy: Float = if (valid) (viewHeight - imageHeight * scale) / 2f else 0f

    fun toView(normalizedX: Float, normalizedY: Float): Offset {
        val x = if (mirror) 1f - normalizedX else normalizedX
        return Offset(
            dx + x * imageWidth * scale,
            dy + normalizedY * imageHeight * scale,
        )
    }

    fun toNormalized(position: Offset): Offset {
        val rawX = (position.x - dx) / (imageWidth * scale)
        val x = if (mirror) 1f - rawX else rawX
        val y = (position.y - dy) / (imageHeight * scale)
        return Offset(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }
}
