package dev.coyotecage.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.coyotecage.CageSettings
import dev.coyotecage.FrameMode
import dev.coyotecage.PointSet
import dev.coyotecage.dglab.DebugEntry
import dev.coyotecage.dglab.DglabV4Client
import dev.coyotecage.dglab.LinkState
import dev.coyotecage.dglab.buildPairUrl
import dev.coyotecage.util.Qr
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: CageSettings,
    debugLog: List<DebugEntry>,
    onChange: (CageSettings) -> Unit,
    onTest: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PANEL,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "参数",
                color = DIM,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 3.sp,
            )

            SectionTitle("强度")
            SliderRow("基础强度", settings.baseIntensity, 1..40) {
                onChange(settings.copy(baseIntensity = it))
            }
            SliderRow("强度上限", settings.maxIntensity, 1..100) {
                onChange(settings.copy(maxIntensity = it))
            }
            SliderRow("每级递增", settings.step, 1..10) {
                onChange(settings.copy(step = it))
            }
            SwitchRow("随越界时间递增", settings.escalate) {
                onChange(settings.copy(escalate = it))
            }
            SliderRow("每 N 秒涨一级", settings.escalateEverySec, 1..30) {
                onChange(settings.copy(escalateEverySec = it))
            }

            SectionTitle("时机")
            SliderRow("单次电击时长 (ms)", settings.shockMs, 100..3000) {
                onChange(settings.copy(shockMs = (it / 50) * 50))
            }
            SliderRow("两次间隔 (ms)", settings.gapMs, 200..10000) {
                onChange(settings.copy(gapMs = (it / 100) * 100))
            }
            SliderRow("越界容忍 (ms)", settings.graceMs, 0..1500) {
                onChange(settings.copy(graceMs = (it / 50) * 50))
            }

            SectionTitle("判定")
            ChoiceRow(
                label = "检测部位",
                options = PointSet.values().toList(),
                selected = settings.pointSet,
                display = { it.label },
                onSelect = { onChange(settings.copy(pointSet = it)) },
            )
            ChoiceRow(
                label = "输出通道",
                options = listOf(0, 1),
                selected = settings.channel,
                display = { if (it == 0) "A" else "B" },
                onSelect = { onChange(settings.copy(channel = it)) },
            )
            ChoiceRow(
                label = "框模式",
                options = FrameMode.values().toList(),
                selected = settings.frameMode,
                display = { it.label },
                onSelect = { onChange(settings.copy(frameMode = it)) },
            )
            SliderRow("开局准备倒计时 (秒)", settings.prepareSec, 0..10) {
                onChange(settings.copy(prepareSec = it))
            }
            if (settings.prepareSec == 0) {
                Text(
                    "0 = 关闭倒计时，按「开始」后立即判定。",
                    color = COLOR_DIM,
                    fontSize = 11.sp,
                )
            }
            if (settings.frameMode == FrameMode.RANDOM) {
                SliderRow("随机间隔 (秒)", settings.randomEverySec, 5..60) {
                    onChange(settings.copy(randomEverySec = it))
                }
                Text(
                    "每 N 秒换一个框，新框不会落在上一个框所在的象限；上半屏概率约 10%；不在框里立刻惩罚，没有容忍时间。",
                    color = COLOR_DIM,
                    fontSize = 11.sp,
                )
            }
            if (settings.frameMode == FrameMode.POSE) {
                SliderRow("姿势容差 (% 画面高度)", settings.poseTolerance, 1..12) {
                    onChange(settings.copy(poseTolerance = it))
                }
                Text(
                    "倒计时归零时记录当前姿势当基准，之后偏差超过容差就立刻惩罚。太小会误触，太大等于没锁。",
                    color = COLOR_DIM,
                    fontSize = 11.sp,
                )
            }

            SectionTitle("波形")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CageSettings.presets.forEach { preset ->
                    OutlinedButton(
                        onClick = { onChange(settings.copy(waveform = preset.second)) },
                    ) {
                        Text(preset.first, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            val valid = CageSettings.parseFrames(settings.waveform) != null
            OutlinedTextField(
                value = settings.waveform,
                onValueChange = { onChange(settings.copy(waveform = it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 110.dp),
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                isError = !valid,
                label = { Text("每行 16 位 HEX，一帧 = 8 字节 = 100ms") },
            )
            if (!valid) {
                Text(
                    text = "格式不对：每行必须正好 16 个十六进制字符",
                    color = COLOR_ALERT,
                    fontSize = 11.sp,
                )
            }

            SectionTitle("连接")
            SwitchRow("调试模式（不连设备，只记录输出）", settings.debugMode) {
                onChange(settings.copy(debugMode = it))
            }
            OutlinedTextField(
                value = settings.relayUrl,
                onValueChange = { onChange(settings.copy(relayUrl = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp),
                label = { Text("中继地址（自建服务端时改这里）") },
            )

            if (settings.debugMode) {
                SectionTitle("调试输出")
                val formatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0E0E11), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                ) {
                    val recent = debugLog.takeLast(10)
                    if (recent.isEmpty()) {
                        Text("还没有输出，点「连接」后跑一次就有了", color = COLOR_DIM, fontSize = 11.sp)
                    } else {
                        recent.forEach { entry ->
                            Text(
                                text = formatter.format(Date(entry.timeMs)) + "  " + entry.text,
                                color = Color(0xFFCCCCCC),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = onTest, modifier = Modifier.fillMaxWidth()) {
                Text("用当前基础强度试一次")
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = text,
        color = BONE.copy(alpha = 0.52f),
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 3.sp,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SliderRow(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = DIM, fontSize = 12.sp)
            Text(
                text = value.toString(),
                color = AMBER,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(AMBER.copy(alpha = 0.07f), RoundedCornerShape(2.dp))
                    .border(1.dp, AMBER.copy(alpha = 0.28f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
        Slider(
            value = value.coerceIn(range.first, range.last).toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = DIM, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    display: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = DIM, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                KeyButton(
                    text = display(option),
                    onClick = { onSelect(option) },
                    compact = true,
                    style = if (option == selected) KeyStyle.Primary else KeyStyle.Ghost,
                )
            }
        }
    }
}

@Composable
fun QrDialog(link: LinkState, relayUrl: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val controllerId = link.controllerId

    val pairUrl = remember(controllerId, relayUrl) {
        if (controllerId == null) null else buildPairUrl(relayUrl, controllerId)
    }

    val qrContent = remember(pairUrl) {
        pairUrl?.let { DglabV4Client.QR_PREFIX + URLEncoder.encode(it, "UTF-8") }
    }

    val qrBitmap = remember(qrContent) {
        qrContent?.let { runCatching { Qr.encode(it) }.getOrNull() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("用 DG-LAB 4 APP 扫码") },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(240.dp),
                    )
                } else {
                    Text("还没拿到配对 ID，先确认已经连上中继", color = COLOR_DIM, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = pairUrl ?: "",
                    color = COLOR_DIM,
                    fontSize = 10.sp,
                    maxLines = 3,
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(onClick = {
                        pairUrl?.let { clipboard.setText(AnnotatedString(it)) }
                    }) {
                        Text("复制连接地址", fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        qrContent?.let { clipboard.setText(AnnotatedString(it)) }
                    }) {
                        Text("复制二维码内容", fontSize = 12.sp)
                    }
                }
                Text(
                    text = "APP 内：设置 → Socket 控制 → 扫码",
                    color = COLOR_DIM,
                    fontSize = 11.sp,
                )
            }
        },
    )
}
