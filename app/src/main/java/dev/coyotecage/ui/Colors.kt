package dev.coyotecage.ui

import androidx.compose.ui.graphics.Color

// 取景器配色：骨白 + 墨黑，三个语义色各司其职
internal val INK = Color(0xFF08090B)
internal val PANEL = Color(0xFF0E1013)
internal val RAISE = Color(0xFF14171B)
internal val LINE = Color(0x1FE9E6DE)          // 骨白 12%
internal val LINE_STRONG = Color(0x4DE9E6DE)   // 骨白 30%
internal val BONE = Color(0xFFE9E6DE)
internal val DIM = Color(0xFF8B9099)
internal val MINT = Color(0xFF6FE3A4)          // 安全 / 在框内
internal val SIGNAL = Color(0xFFFF4B1F)        // 越界 / 急停
internal val AMBER = Color(0xFFF2B33D)         // 数字与时间

// 旧命名保留，语义不变
internal val COLOR_OK = MINT
internal val COLOR_ALERT = SIGNAL
internal val COLOR_PANEL = PANEL
internal val COLOR_DIM = DIM
