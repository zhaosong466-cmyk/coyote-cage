package dev.coyotecage.shell

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 整个 App 就是一个 WebView，加载线上网页。
 *
 * 目的：网页更新之后手机端不用重新发版，装了这个壳就等于一直是最新版。
 * 版本号 [SHELL_VERSION] 只代表这个壳本身，和网页端版本无关。
 */
class MainActivity : Activity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString + " CoyoteShell/" + SHELL_VERSION
        }

        web.webViewClient = object : WebViewClient() {
            /** 站内链接留在 WebView 里，外部链接交给系统浏览器。 */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == SITE_HOST) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            /** 网页里的 getUserMedia 会走到这里，只放行摄像头，其它一律拒绝。 */
            override fun onPermissionRequest(request: PermissionRequest) {
                val video = request.resources.firstOrNull { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
                if (video != null && cameraGranted()) {
                    request.grant(arrayOf(video))
                } else {
                    request.deny()
                }
            }
        }

        // 先把系统相机权限拿到再开页面，避免第一次 getUserMedia 撞上还没授权的状态
        if (cameraGranted()) openPage() else requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 拒绝也照常开页面，网页自己会提示摄像头打不开
        if (requestCode == REQ_CAMERA) openPage()
    }

    private fun openPage() {
        if (web.url == null) web.loadUrl(START_URL)
    }

    private fun cameraGranted(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private companion object {
        const val SHELL_VERSION = "1145.14.191810"
        const val SITE_HOST = "zhaosong466-cmyk.github.io"
        const val START_URL = "https://zhaosong466-cmyk.github.io/coyote-cage/"
        const val REQ_CAMERA = 1
    }
}
