package dev.coyotecage

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.coyotecage.ui.BONE
import dev.coyotecage.ui.CageApp
import dev.coyotecage.ui.DIM
import dev.coyotecage.ui.INK
import dev.coyotecage.ui.LINE_STRONG
import dev.coyotecage.ui.MINT
import dev.coyotecage.ui.PANEL
import dev.coyotecage.ui.RAISE

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 长时间盯着摄像头，别让屏幕睡着。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 把 Material 的默认紫调换掉，滑块/开关/输入框都走骨白 + 薄荷
        val scheme = darkColorScheme(
            primary = BONE,
            onPrimary = INK,
            secondary = MINT,
            onSecondary = INK,
            background = INK,
            onBackground = BONE,
            surface = PANEL,
            onSurface = BONE,
            surfaceVariant = RAISE,
            onSurfaceVariant = DIM,
            outline = LINE_STRONG,
            error = Color(0xFFFF4B1F),
        )

        setContent {
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = INK) {
                    CageApp()
                }
            }
        }
    }
}
