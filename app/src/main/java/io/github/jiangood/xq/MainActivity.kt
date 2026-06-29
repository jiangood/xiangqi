package io.github.jiangood.xq

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.jiangood.xq.analysis.AnalysisEngine
import io.github.jiangood.xq.BuildConfig
import io.github.jiangood.xq.service.CaptureState
import io.github.jiangood.xq.service.FloatingBubbleService
import io.github.jiangood.xq.settings.SettingsManager
import io.github.jiangood.xq.ui.CalibrationScreen
import io.github.jiangood.xq.ui.MainScreen
import io.github.jiangood.xq.ui.SettingsScreen
import io.github.jiangood.xq.util.AppLog
import io.github.jiangood.xq.viewmodel.AnalysisViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AnalysisViewModel by viewModels()

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            viewModel.analyze(this, uri)
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            AppLog.add("[悬浮窗] 悬浮窗权限已获取")
            checkAndRequestNotificationPermission()
        } else {
            AppLog.add("[悬浮窗] 用户未授予悬浮窗权限")
            Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            AppLog.add("[悬浮窗] 需要通知权限，发起请求")
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            AppLog.add("[悬浮窗] 通知权限已获取")
            requestScreenCaptureAndStartService()
        }
    }

    private fun requestScreenCaptureAndStartService() {
        if (CaptureState.mediaProjection == null) {
            AppLog.add("[悬浮窗] 请求截屏权限")
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            AppLog.add("[悬浮窗] MediaProjection 已存在，直接启动服务")
            startFloatingService()
        }
    }

    private var notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            AppLog.add("[悬浮窗] 通知权限已授予")
            requestScreenCaptureAndStartService()
        } else {
            AppLog.add("[悬浮窗] 用户未授予通知权限")
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            if (result.resultCode == RESULT_OK && result.data != null) {
                AppLog.add("[悬浮窗] 截屏权限已获取，发送至服务")
                val intent = Intent(this, FloatingBubbleService::class.java).apply {
                    action = "SET_MEDIA_PROJECTION"
                    putExtra("result_code", result.resultCode)
                    putExtra("result_data", result.data)
                }
                startService(intent)
                // Now start the floating service (or it may already be running)
                startFloatingService()
            } else {
                AppLog.add("[悬浮窗] 用户未授权截屏")
                Toast.makeText(this, "未授权截屏，截屏分析功能不可用", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            AppLog.add("[悬浮窗] 截屏授权回调异常: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppLog.init(this)
        SettingsManager.init(this)
        AppLog.add("版本: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        viewModel.initOpenCV(this)
        AnalysisEngine.init(this)

        if (intent.action == "REQUEST_SCREEN_CAPTURE") {
            requestScreenCapturePermission()
        }

        setContent {
            var showSettings by remember { mutableStateOf(false) }
            var showCalibration by remember { mutableStateOf(false) }

            when {
                showCalibration -> {
                    CalibrationScreen(onBack = { showCalibration = false })
                }
                showSettings -> {
                    SettingsScreen(
                        onBack = { showSettings = false },
                        onOpenCalibration = { showCalibration = true }
                    )
                }
                else -> {
                    MainScreen(
                        viewModel = viewModel,
                        onPickImage = {
                            pickMedia.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onToggleFloating = { enabled ->
                            if (enabled) {
                                AppLog.add("[悬浮窗] 用户开启悬浮窗")
                                if (!Settings.canDrawOverlays(this)) {
                                    AppLog.add("[悬浮窗] 需要悬浮窗权限，跳转设置")
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:$packageName")
                                    )
                                    overlayPermissionLauncher.launch(intent)
                                } else {
                                    AppLog.add("[悬浮窗] 已有悬浮窗权限")
                                    checkAndRequestNotificationPermission()
                                }
                            } else {
                                AppLog.add("[悬浮窗] 用户关闭悬浮窗")
                                Intent(this, FloatingBubbleService::class.java).also {
                                    it.action = "STOP"
                                    startService(it)
                                }
                            }
                        },
                        onOpenSettings = { showSettings = true }
                    )
                }
            }
        }
    }

    private fun startFloatingService() {
        startForegroundService(Intent(this, FloatingBubbleService::class.java))
    }

    private fun requestScreenCapturePermission() {
        AppLog.add("[悬浮窗] 请求截屏权限")
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
