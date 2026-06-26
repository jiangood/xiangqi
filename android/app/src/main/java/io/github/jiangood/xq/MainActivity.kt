package io.github.jiangood.xq

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import io.github.jiangood.xq.BuildConfig
import io.github.jiangood.xq.service.CaptureState
import io.github.jiangood.xq.service.FloatingBubbleService
import io.github.jiangood.xq.ui.MainScreen
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            AppLog.add("[悬浮窗] 悬浮窗权限已获取")
            checkAndRequestNotificationPermission()
        } else {
            AppLog.add("[悬浮窗] 用户未授予悬浮窗权限")
            Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                AppLog.add("[悬浮窗] 需要通知权限，发起请求")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                AppLog.add("[悬浮窗] 通知权限已获取，启动服务")
                startFloatingService()
            }
        } else {
            AppLog.add("[悬浮窗] Android < 13，无需通知权限，直接启动服务")
            startFloatingService()
        }
    }

    private var notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            AppLog.add("[悬浮窗] 通知权限已授予")
            startFloatingService()
        } else {
            AppLog.add("[悬浮窗] 用户未授予通知权限")
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            if (result.resultCode == RESULT_OK && result.data != null) {
                AppLog.add("[悬浮窗] 截屏权限已获取")
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = mpm.getMediaProjection(result.resultCode, result.data!!)
                if (projection == null) {
                    AppLog.add("[悬浮窗] getMediaProjection 返回 null")
                    return@registerForActivityResult
                }
                projection.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        AppLog.add("[悬浮窗] mediaProjection 停止")
                        CaptureState.mediaProjection = null
                    }
                }, null)
                CaptureState.mediaProjection = projection
                AppLog.add("[悬浮窗] mediaProjection 已保存")
                if (CaptureState.pendingCaptureRequest) {
                    AppLog.add("[悬浮窗] 有挂起的截屏请求，立即执行")
                    CaptureState.pendingCaptureRequest = false
                    Intent(this, FloatingBubbleService::class.java).also {
                        it.action = "CAPTURE_NOW"
                        startService(it)
                    }
                } else {
                    AppLog.add("[悬浮窗] 无挂起截屏请求")
                }
            } else {
                AppLog.add("[悬浮窗] 用户未授权截屏")
            }
        } catch (e: Exception) {
            AppLog.add("[悬浮窗] 截屏授权回调异常: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppLog.init(this)
        AppLog.add("版本: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        viewModel.initOpenCV(this)
        viewModel.initEngine(this)
        viewModel.initRecognizer(this)

        handleShareIntent(intent)
        handleRequestCapture(intent)

        setContent {
            MainScreen(
                viewModel = viewModel,
                onPickImage = { pickMedia.launch(ActivityResultContracts.PickVisualMedia.ImageOnly.let { androidx.activity.result.PickVisualMediaRequest(it) }) },
                onToggleFloating = { enabled ->
                    if (enabled) {
                        AppLog.add("[悬浮窗] 用户开启悬浮窗")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
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
                }
            )
        }
    }

    private fun checkNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                startFloatingService()
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startFloatingService()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
        handleRequestCapture(intent)
    }

    private fun handleShareIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri != null) {
                viewModel.analyze(this, uri)
            }
        }
    }

    private fun handleRequestCapture(intent: Intent) {
        if (intent.action == "REQUEST_CAPTURE") {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    private fun startFloatingService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, FloatingBubbleService::class.java))
        } else {
            startService(Intent(this, FloatingBubbleService::class.java))
        }
    }
}
