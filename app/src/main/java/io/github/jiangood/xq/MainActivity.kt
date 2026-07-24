package io.github.jiangood.xq

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.github.jiangood.xq.analysis.AnalysisEngine
import io.github.jiangood.xq.service.CaptureOverlayService
import io.github.jiangood.xq.BuildConfig
import io.github.jiangood.xq.ui.MainScreen
import io.github.jiangood.xq.ui.SettingsScreen
import io.github.jiangood.xq.util.AppLog
import io.github.jiangood.xq.util.GlobalExceptionHandler
import io.github.jiangood.xq.viewmodel.AnalysisViewModel

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_ANALYZE_SCREENSHOT = "io.github.jiangood.xq.action.ANALYZE_SCREENSHOT"
    }

    private val viewModel: AnalysisViewModel by viewModels()

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        projectionRequested = true
        if (result.resultCode == RESULT_OK && result.data != null) {
            AppLog.add("[悬浮窗] 用户已授权屏幕投影")
            Intent(this, CaptureOverlayService::class.java).apply {
                action = CaptureOverlayService.ACTION_SET_PROJECTION
                putExtra(CaptureOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureOverlayService.EXTRA_DATA, result.data)
            }.also { startForegroundService(it) }
        } else {
            AppLog.add("[悬浮窗] 用户未授权屏幕投影")
        }
    }

    private val showPermissionDialog = mutableStateOf(false)
    private var projectionRequested = false
    private var overlayDialogDismissed = false  // 跟踪用户是否主动关过弹窗

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 恢复已请求过投影的状态，避免 Activity 重建后重复弹窗
        projectionRequested = savedInstanceState?.getBoolean("projectionRequested", false) ?: false
        overlayDialogDismissed = savedInstanceState?.getBoolean("overlayDialogDismissed", false) ?: false

        AppLog.clear()
        AppLog.init(this)
        AppLog.add("版本: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        viewModel.initOpenCV(this)
        AnalysisEngine.init(this)

        handleScreenshotIntent(intent)

        setContent {
            var showSettings by remember { mutableStateOf(false) }
            val exception by GlobalExceptionHandler.exceptionEvent.collectAsState()

            when {
                showSettings -> {
                    SettingsScreen(
                        onBack = { showSettings = false }
                    )
                }
                else -> {
                    MainScreen(
                        viewModel = viewModel,
                        onRequestProjection = { requestScreenCapture() },
                        onOpenSettings = { showSettings = true }
                    )
                }
            }

            if (showPermissionDialog.value) {
                AlertDialog(
                    onDismissRequest = {
                        showPermissionDialog.value = false
                        overlayDialogDismissed = true  // 点外部/返回 → 不再反复弹
                    },
                    title = { Text("需要悬浮窗权限") },
                    text = { Text("象棋支招需要悬浮窗权限来在屏幕上显示推荐走法。") },
                    confirmButton = {
                        TextButton(onClick = {
                            showPermissionDialog.value = false
                            overlayDialogDismissed = false  // 去设置页面回来还要继续弹，直到用户授权
                            startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                            )
                        }) { Text("去设置") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showPermissionDialog.value = false
                            overlayDialogDismissed = true  // 用户点了取消 → 不再反复弹
                        }) { Text("取消") }
                    }
                )
            }

            exception?.let { throwable ->
                AlertDialog(
                    onDismissRequest = { GlobalExceptionHandler.dismiss() },
                    title = { Text("异常错误") },
                    text = {
                        SelectionContainer {
                            Text(
                                text = throwable.stackTraceToString(),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("crash", throwable.stackTraceToString()))
                            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("复制")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            GlobalExceptionHandler.dismiss()
                            finishAffinity()
                        }) {
                            Text("关闭")
                        }
                    }
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("projectionRequested", projectionRequested)
        outState.putBoolean("overlayDialogDismissed", overlayDialogDismissed)
    }

    override fun onResume() {
        super.onResume()
        val hasOverlay = Settings.canDrawOverlays(this)
        if (!hasOverlay) {
            // 只有用户之前没有主动关掉弹窗时才再显示
            if (!overlayDialogDismissed) {
                showPermissionDialog.value = true
            }
        } else {
            showPermissionDialog.value = false
            overlayDialogDismissed = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleScreenshotIntent(intent)
    }

    private fun handleScreenshotIntent(intent: Intent?) {
        if (intent?.action != ACTION_ANALYZE_SCREENSHOT) return
        val uri = intent.data ?: return
        AppLog.add("[悬浮窗] 通过选图路径分析截图: $uri")
        viewModel.analyze(this, uri)
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
