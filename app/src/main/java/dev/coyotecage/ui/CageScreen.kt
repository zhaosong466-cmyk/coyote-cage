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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
                    ui = engineUi,
                    randomMode = randomMode,
                    poseMode = poseMode,
                    poseTolerance = settings.poseTolerance,
                    randomRemaining = randomRemaining,
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
                    ui = engineUi,
                    randomMode = randomMode,
                    poseMode = poseMode,
                    poseTolerance = settings.poseTolerance,
                    randomRemaining = randomRemaining,
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
    ui: EngineUi,
    randomMode: Boolean,
    poseMode: Boolean,
    poseTolerance: Int,
    randomRemaining: Int,
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
                Text("需要相机权限", color = BONE)
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
            violating = ui.violating,
            showFrameBox = !poseMode,
        )

        // 运行指示灯：舞台顶部的琥珀细线
        if (ui.running) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(AMBER),
            )
        }

        // 左上：状态
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 22.dp, top = 16.dp),
        ) {
            Text(
                text = "STATE",
                color = BONE.copy(alpha = 0.42f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
            )
            Text(
                text = statusLabel(ui),
                color = phaseColor(ui),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                style = TextStyle(
                    shadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 1f), 4f),
                ),
            )
        }

        // 左下：遥测
        Text(
            text = meterAnnotated(ui, randomMode, poseMode, poseTolerance, randomRemaining),
            color = DIM,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            style = TextStyle(
                shadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 1f), 4f),
            ),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 22.dp, bottom = 16.dp)
                .widthIn(max = 300.dp),
        )

        if (prepareSeconds > 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = prepareSeconds.toString(),
                        color = AMBER,
                        fontSize = 88.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (poseMode) "摆好姿势" else "准备",
                        color = BONE.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp,
                    )
                }
            }
        }

        if (selecting) {
            Text(
                text = "拖动框出判定区域",
                color = BONE,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp)
                    .background(Color(0xCC08090B), RoundedCornerShape(2.dp))
                    .border(1.dp, LINE, RoundedCornerShape(2.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }

        if (!ui.poseFresh && ui.running && !ui.preparing) {
            Text(
                text = "没检测到人，已暂停判定",
                color = SIGNAL,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .background(Color(0xCC08090B), RoundedCornerShape(2.dp))
                    .border(1.dp, LINE, RoundedCornerShape(2.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

private fun phaseColor(ui: EngineUi): Color = when {
    ui.preparing -> AMBER
    ui.violating -> SIGNAL
    ui.running && ui.poseFresh -> MINT
    else -> DIM
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
            .background(PANEL)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("画地为牢", color = BONE, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(
            text = "ZONE",
            color = DIM,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp,
            modifier = Modifier
                .border(1.dp, LINE, RoundedCornerShape(2.dp))
                .padding(horizontal = 5.dp, vertical = 3.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = headerStatus(link),
            color = DIM,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 170.dp),
        )
        Spacer(Modifier.width(10.dp))
        when (link.phase) {
            LinkPhase.IDLE -> KeyButton("连接", onConnect, compact = true, style = KeyStyle.Ghost)
            LinkPhase.ERROR -> KeyButton("重连", onConnect, compact = true, style = KeyStyle.Ghost)
            LinkPhase.CONNECTING -> Text("连接中…", color = DIM, fontSize = 11.sp)
            LinkPhase.WAITING_APP -> KeyButton("二维码", onShowQr, compact = true, style = KeyStyle.Ghost)
            LinkPhase.PAIRED -> KeyButton("断开", onDisconnect, compact = true, style = KeyStyle.Ghost)
        }
    }
}

private fun headerStatus(link: LinkState): String = when (link.phase) {
    LinkPhase.IDLE -> "未连接"
    LinkPhase.CONNECTING -> "连接中"
    LinkPhase.WAITING_APP -> "待扫码"
    LinkPhase.PAIRED -> {
        val dev = link.devices.firstOrNull()?.name ?: "已连接"
        "$dev · A${link.intensityA}/B${link.intensityB}"
    }
    LinkPhase.ERROR -> link.message ?: "连接异常"
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

// --------------------------------------------------------------------- 按键

internal enum class KeyStyle { Normal, Primary, Danger, Ghost }

@Composable
internal fun KeyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: KeyStyle = KeyStyle.Normal,
    compact: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(2.dp)
    val bg = when (style) {
        KeyStyle.Primary -> BONE
        KeyStyle.Danger -> SIGNAL
        KeyStyle.Ghost -> Color.Transparent
        KeyStyle.Normal -> RAISE
    }
    val fg = when (style) {
        KeyStyle.Primary -> INK
        KeyStyle.Danger -> Color.White
        else -> BONE
    }
    val borderColor = when (style) {
        KeyStyle.Primary -> BONE
        KeyStyle.Danger -> SIGNAL
        else -> LINE
    }

    Box(
        modifier
            .then(if (compact) Modifier.height(32.dp) else Modifier.height(46.dp))
            .alpha(if (enabled) 1f else 0.32f)
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = if (compact) 12.sp else 13.sp,
            fontWeight = if (style == KeyStyle.Primary || style == KeyStyle.Danger) {
                FontWeight.SemiBold
            } else {
                FontWeight.Medium
            },
            modifier = Modifier.padding(horizontal = if (compact) 10.dp else 12.dp),
        )
    }
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
            .background(PANEL)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KeyButton(
                text = if (ui.running) "停止" else "开始",
                onClick = onToggleRun,
                enabled = ready || ui.running,
                style = KeyStyle.Primary,
                modifier = Modifier.weight(1.4f),
            )
            KeyButton(
                text = if (selecting) "完成" else "框选",
                onClick = onToggleSelect,
                enabled = !randomMode && !poseMode,
                modifier = Modifier.weight(1f),
            )
            KeyButton("镜头", onSwitchCamera, modifier = Modifier.weight(1f))
            KeyButton("设置", onSettings, modifier = Modifier.weight(1f))
            KeyButton(
                text = "急停",
                onClick = onEmergency,
                style = KeyStyle.Danger,
                modifier = Modifier.width(84.dp),
            )
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
            .background(PANEL)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("画地为牢", color = BONE, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(
                "ZONE",
                color = DIM,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.5.sp,
            )
        }
        Text(
            text = headerStatus(link),
            color = DIM,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
        )

        when (link.phase) {
            LinkPhase.IDLE -> KeyButton("连接", onConnect, compact = true, style = KeyStyle.Ghost, modifier = Modifier.fillMaxWidth())
            LinkPhase.ERROR -> KeyButton("重连", onConnect, compact = true, style = KeyStyle.Ghost, modifier = Modifier.fillMaxWidth())
            LinkPhase.CONNECTING -> Text("连接中…", color = DIM, fontSize = 11.sp)
            LinkPhase.WAITING_APP -> KeyButton("二维码", onShowQr, compact = true, style = KeyStyle.Ghost, modifier = Modifier.fillMaxWidth())
            LinkPhase.PAIRED -> KeyButton("断开", onDisconnect, compact = true, style = KeyStyle.Ghost, modifier = Modifier.fillMaxWidth())
        }

        if (debugMode) {
            Column(Modifier.fillMaxWidth().background(INK, RoundedCornerShape(2.dp)).padding(6.dp)) {
                debugLog.takeLast(3).forEach { entry ->
                    Text(
                        text = formatter.format(Date(entry.timeMs)) + " " + entry.text,
                        color = BONE.copy(alpha = 0.7f),
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        KeyButton(
            text = if (ui.running) "停止" else "开始",
            onClick = onToggleRun,
            enabled = ready || ui.running,
            style = KeyStyle.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
        KeyButton(
            text = if (selecting) "完成" else "框选",
            onClick = onToggleSelect,
            enabled = !randomMode && !poseMode,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("镜头", onSwitchCamera, modifier = Modifier.weight(1f))
            KeyButton("设置", onSettings, modifier = Modifier.weight(1f))
        }
        KeyButton(
            text = "急停",
            onClick = onEmergency,
            style = KeyStyle.Danger,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 四角括号（取景器语言，判定框和越界框共用）。
 */
private fun DrawScope.drawCornerBrackets(
    rect: Rect,
    len: Float,
    color: Color,
    strokeWidth: Float,
) {
    val l = rect.left
    val t = rect.top
    val r = rect.right
    val b = rect.bottom
    val lenX = min(len, (r - l) / 2f)
    val lenY = min(len, (b - t) / 2f)

    drawLine(color, Offset(l, t + lenY), Offset(l, t), strokeWidth = strokeWidth)
    drawLine(color, Offset(l, t), Offset(l + lenX, t), strokeWidth = strokeWidth)
    drawLine(color, Offset(r - lenX, t), Offset(r, t), strokeWidth = strokeWidth)
    drawLine(color, Offset(r, t), Offset(r, t + lenY), strokeWidth = strokeWidth)
    drawLine(color, Offset(r, b - lenY), Offset(r, b), strokeWidth = strokeWidth)
    drawLine(color, Offset(r, b), Offset(r - lenX, b), strokeWidth = strokeWidth)
    drawLine(color, Offset(l + lenX, b), Offset(l, b), strokeWidth = strokeWidth)
    drawLine(color, Offset(l, b), Offset(l, b - lenY), strokeWidth = strokeWidth)
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

private fun meterAnnotated(
    ui: EngineUi,
    randomMode: Boolean,
    poseMode: Boolean,
    poseTolerance: Int,
    randomRemaining: Int,
): AnnotatedString = buildAnnotatedString {
    val amber = SpanStyle(color = AMBER, fontWeight = FontWeight.SemiBold)
    fun value(text: String) = withStyle(amber) { append(text) }

    when {
        ui.preparing -> {
            append("倒计时 ")
            value(ceil(ui.prepareRemainingMs / 1000.0).toInt().coerceAtLeast(1).toString())
            append("s")
        }

        poseMode -> {
            append("偏差 ")
            value(String.format("%.1f", ui.deviationPercent))
            append("% / 容差 ")
            value(poseTolerance.toString())
            append("% · 强度 ")
            value(ui.targetIntensity.toString())
        }

        else -> {
            append("越界 ")
            value(String.format("%.1f", ui.outsideMs / 1000f))
            append("s · 强度 ")
            value(ui.targetIntensity.toString())
            if (randomMode && ui.running) {
                append(" · 下一个框 ")
                value(randomRemaining.toString())
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
            .background(INK)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = "DEBUG · 不连设备，仅记录",
            color = AMBER.copy(alpha = 0.8f),
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.5.sp,
        )
        val recent = entries.takeLast(3)
        if (recent.isEmpty()) {
            Text("等待输出…", color = DIM, fontSize = 10.sp)
        } else {
            recent.forEach { entry ->
                Text(
                    text = formatter.format(Date(entry.timeMs)) + "  " + entry.text,
                    color = BONE.copy(alpha = 0.8f),
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

            // 越界：整屏细框 + 四角加粗
            if (violating) {
                drawRect(
                    color = SIGNAL.copy(alpha = 0.5f),
                    topLeft = Offset(1f, 1f),
                    size = Size(size.width - 2f, size.height - 2f),
                    style = Stroke(width = 2f),
                )
                drawCornerBrackets(
                    rect = Rect(8f, 8f, size.width - 8f, size.height - 8f),
                    len = 34f,
                    color = SIGNAL,
                    strokeWidth = 4f,
                )
            }

            // 取景框括线（常驻）
            drawCornerBrackets(
                rect = Rect(10f, 10f, size.width - 10f, size.height - 10f),
                len = 20f,
                color = BONE.copy(alpha = 0.32f),
                strokeWidth = 1.5f,
            )

            // 前置摄像头是镜像的，两个角的先后顺序会反过来，所以统一取 min/max。
            val cornerA = overlayMap.toView(roi.left, roi.top)
            val cornerB = overlayMap.toView(roi.right, roi.bottom)
            val boxLeft = min(cornerA.x, cornerB.x)
            val boxTop = min(cornerA.y, cornerB.y)
            val boxWidth = max(cornerA.x, cornerB.x) - boxLeft
            val boxHeight = max(cornerA.y, cornerB.y) - boxTop
            val boxColor = if (violating) SIGNAL else MINT

            if (showFrameBox) {
                drawRect(
                    color = boxColor.copy(alpha = 0.07f),
                    topLeft = Offset(boxLeft, boxTop),
                    size = Size(boxWidth, boxHeight),
                )
                drawRect(
                    color = boxColor.copy(alpha = 0.55f),
                    topLeft = Offset(boxLeft, boxTop),
                    size = Size(boxWidth, boxHeight),
                    style = Stroke(
                        width = 1.5f,
                        pathEffect = if (selecting) {
                            PathEffect.dashPathEffect(floatArrayOf(16f, 14f))
                        } else {
                            null
                        },
                    ),
                )
                drawCornerBrackets(
                    rect = Rect(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight),
                    len = min(22f, min(boxWidth, boxHeight) * 0.28f),
                    color = boxColor,
                    strokeWidth = 3f,
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
                    color = if (ok) MINT else SIGNAL,
                    start = from,
                    end = to,
                    strokeWidth = 2.5f,
                    cap = StrokeCap.Round,
                )
            }

            for (index in points.indices) {
                val center = screenPoints[index] ?: continue
                val point = points[index]
                val ok = if (showFrameBox) roi.contains(point.x, point.y) else !violating
                drawCircle(
                    color = if (ok) MINT else SIGNAL,
                    radius = 4f,
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
