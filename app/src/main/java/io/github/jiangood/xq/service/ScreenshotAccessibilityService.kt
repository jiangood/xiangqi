package io.github.jiangood.xq.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import io.github.jiangood.xq.analysis.AnalysisEngine
import io.github.jiangood.xq.settings.SettingsManager
import io.github.jiangood.xq.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

class ScreenshotAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var unifiedView: UnifiedBubbleView? = null
    private var isAnalyzing = false

    override fun onCreate() {
        super.onCreate()
        AppLog.add("[无障碍] onCreate")
        showUnifiedView()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        AppLog.add("[无障碍] onUnbind")
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AppLog.add("[无障碍] onDestroy")
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        scope.cancel()
        try {
            unifiedView?.let { v ->
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                wm.removeView(v)
            }
        } catch (_: Exception) {}
        unifiedView = null
    }

    private fun showUnifiedView() {
        AppLog.add("[无障碍] 显示悬浮球...")
        try {
            val density = resources.displayMetrics.density
            val width = (100 * density).toInt()
            val savedX = SettingsManager.getFloatX()
            val savedY = SettingsManager.getFloatY()
            val params = WindowManager.LayoutParams(
                width, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (savedX >= 0 && savedY >= 0) {
                    x = savedX
                    y = savedY
                } else {
                    val metrics = DisplayMetrics()
                    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                    wm.defaultDisplay.getRealMetrics(metrics)
                    val rightMarginDp = 80f
                    val bottomMarginDp = 120f
                    x = metrics.widthPixels - width - (rightMarginDp * density).toInt()
                    y = metrics.heightPixels - (100 * density).toInt() - (bottomMarginDp * density).toInt()
                }
            }
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            unifiedView = UnifiedBubbleView(this).apply {
                onClick = { onUnifiedClick() }
            }
            wm.addView(unifiedView, params)
            AppLog.add("[无障碍] 悬浮球已添加到窗口")
        } catch (e: Exception) {
            AppLog.add("[无障碍] 显示悬浮球失败: ${e.message}")
        }
    }

    private fun onUnifiedClick() {
        if (isAnalyzing) {
            AppLog.add("[无障碍] 正在分析中，忽略本次点击")
            return
        }
        AppLog.add("[无障碍] 按钮被点击，开始截图分析")
        unifiedView?.updateState(UnifiedBubbleView.State.PROCESSING)
        captureAndAnalyze()
    }

    private fun captureAndAnalyze() {
        isAnalyzing = true
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    captureScreenshot()
                }
                if (file != null) {
                    AppLog.add("[无障碍] 截屏成功: ${file.name} (${file.length()} bytes)")
                    AppLog.add("[无障碍] 开始分析...")
                    val result = AnalysisEngine.analyze(file)
                    withContext(Dispatchers.Main) {
                        if (result != null && result.chineseMoves.isNotEmpty()) {
                            AppLog.add("[无障碍] 分析成功, FEN=${result.fen}")
                            unifiedView?.updateState(UnifiedBubbleView.State.SUCCESS, move = result.chineseMoves[0])
                            delayAutoIdle()
                        } else {
                            AppLog.add("[无障碍] 分析无结果")
                            unifiedView?.updateState(UnifiedBubbleView.State.FAILED, error = "无结果")
                            delayAutoIdle()
                        }
                    }
                } else {
                    AppLog.add("[无障碍] 截屏失败")
                    unifiedView?.updateState(UnifiedBubbleView.State.FAILED, error = "截屏失败")
                    delayAutoIdle()
                }
            } catch (e: Exception) {
                AppLog.add("[无障碍] 截图分析异常: ${e.message}")
                unifiedView?.updateState(UnifiedBubbleView.State.FAILED, error = e.message)
                delayAutoIdle()
            } finally {
                isAnalyzing = false
            }
        }
    }

    private suspend fun captureScreenshot(): File? = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { }

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            java.util.concurrent.Executors.newSingleThreadExecutor(),
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val hb = result.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(hb, null)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        if (bitmap != null) {
                            val file = File(cacheDir, "screenshot.png")
                            FileOutputStream(file).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            bitmap.recycle()
                            cont.resume(file)
                        } else {
                            cont.resume(null)
                        }
                    } catch (e: Exception) {
                        AppLog.add("[无障碍] 截屏转换异常: ${e.message}")
                        cont.resume(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    AppLog.add("[无障碍] 系统截屏失败, errorCode=$errorCode")
                    cont.resume(null)
                }
            }
        )
    }

    private var idleJob: kotlinx.coroutines.Job? = null

    private fun delayAutoIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(5000)
            unifiedView?.updateState(UnifiedBubbleView.State.IDLE)
        }
    }
}
