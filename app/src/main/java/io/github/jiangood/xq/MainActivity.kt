package io.github.jiangood.xq

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
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
import io.github.jiangood.xq.BuildConfig
import io.github.jiangood.xq.service.UnifiedBubbleView
import io.github.jiangood.xq.settings.SettingsManager
import io.github.jiangood.xq.ui.CalibrationScreen
import io.github.jiangood.xq.ui.MainScreen
import io.github.jiangood.xq.ui.SettingsScreen
import io.github.jiangood.xq.util.AppLog
import io.github.jiangood.xq.util.GlobalExceptionHandler
import io.github.jiangood.xq.viewmodel.AnalysisViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AnalysisViewModel by viewModels()

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            viewModel.analyze(this, uri)
        }
    }

    private var overlayView: UnifiedBubbleView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppLog.init(this)
        SettingsManager.init(this)
        AppLog.add("版本: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        viewModel.initOpenCV(this)
        AnalysisEngine.init(this)

        setContent {
            var showSettings by remember { mutableStateOf(false) }
            var showCalibration by remember { mutableStateOf(false) }
            val exception by GlobalExceptionHandler.exceptionEvent.collectAsState()

            when {
                showCalibration -> {
                    CalibrationScreen(onBack = { showCalibration = false })
                }
                showSettings -> {
                    SettingsScreen(
                        onBack = { showSettings = false },
                        onOpenCalibration = { showCalibration = true },
                        onOpenAccessibility = {
                            AppLog.add("[无障碍] 引导用户前往设置启用")
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onOpenOverlayPermission = {
                            AppLog.add("[悬浮窗] 引导用户开启悬浮窗权限")
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                        }
                    )
                }
                else -> {
                    var isOverlayVisible by remember { mutableStateOf(false) }
                    MainScreen(
                        viewModel = viewModel,
                        onPickImage = {
                            pickMedia.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onToggleOverlay = { visible ->
                            if (visible) {
                                if (canDrawOverlays()) {
                                    showOverlay()
                                    isOverlayVisible = true
                                } else {
                                    AppLog.add("[悬浮窗] 缺少悬浮窗权限，引导开启")
                                    Toast.makeText(this, "请在设置中开启悬浮窗权限", Toast.LENGTH_LONG).show()
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:$packageName")
                                    )
                                    startActivity(intent)
                                }
                            } else {
                                hideOverlay()
                                isOverlayVisible = false
                            }
                        },
                        isOverlayVisible = isOverlayVisible,
                        onOpenSettings = { showSettings = true }
                    )
                }
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

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun showOverlay() {
        if (overlayView != null) return
        try {
            val density = resources.displayMetrics.density
            val width = (100 * density).toInt()
            val savedX = SettingsManager.getFloatX()
            val savedY = SettingsManager.getFloatY()
            val params = WindowManager.LayoutParams(
                width, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (savedX >= 0 && savedY >= 0) {
                    x = savedX
                    y = savedY
                } else {
                    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                    val metrics = android.util.DisplayMetrics()
                    wm.defaultDisplay.getRealMetrics(metrics)
                    val rightMarginDp = 80f
                    val bottomMarginDp = 120f
                    x = metrics.widthPixels - width - (rightMarginDp * density).toInt()
                    y = metrics.heightPixels - (100 * density).toInt() - (bottomMarginDp * density).toInt()
                }
            }
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            overlayView = UnifiedBubbleView(this).apply {
                onClick = {
                    pickMedia.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            }
            wm.addView(overlayView, params)
            AppLog.add("[悬浮窗] 悬浮球已显示")
        } catch (e: Exception) {
            AppLog.add("[悬浮窗] 显示失败: ${e.message}")
        }
    }

    private fun hideOverlay() {
        try {
            overlayView?.let { v ->
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                wm.removeView(v)
            }
        } catch (_: Exception) {}
        overlayView = null
        AppLog.add("[悬浮窗] 悬浮球已隐藏")
    }
}
