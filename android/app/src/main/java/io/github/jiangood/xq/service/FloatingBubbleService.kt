package io.github.jiangood.xq.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import io.github.jiangood.xq.analysis.AnalysisEngine
import io.github.jiangood.xq.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object CaptureState {
    var mediaProjection: MediaProjection? = null
}

class FloatingBubbleService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var windowManager: WindowManager
    private var bubbleView: BubbleView? = null
    private var resultOverlay: View? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.add("[悬浮窗] onCreate")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        AppLog.add("[悬浮窗] WindowManager 获取成功")
        if (!startForegroundSafe()) {
            AppLog.add("[悬浮窗] 前台服务启动失败，停止服务")
            stopSelf()
            return
        }
        AppLog.add("[悬浮窗] 前台服务启动成功")
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.add("[悬浮窗] onStartCommand action=${intent?.action ?: "无"}")
        when (intent?.action) {
            "CAPTURE_NOW" -> {
                AppLog.add("[悬浮窗] 收到截屏指令")
                captureAndAnalyze()
            }
            "STOP" -> {
                AppLog.add("[悬浮窗] 收到停止指令")
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AppLog.add("[悬浮窗] onDestroy")
        scope.cancel()
        try {
            resultOverlay?.let { windowManager.removeView(it) }
            bubbleView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startForegroundSafe(): Boolean {
        return try {
            val channelId = "floating_bubble_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AppLog.add("[悬浮窗] 创建通知渠道...")
                val channel = android.app.NotificationChannel(
                    channelId, "悬浮窗", NotificationManager.IMPORTANCE_LOW
                )
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            }
            AppLog.add("[悬浮窗] 构建通知...")
            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("象棋支招")
                .setContentText("悬浮窗运行中，点击截图分析")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
            startForeground(1, notification)
            AppLog.add("[悬浮窗] startForeground 成功")
            true
        } catch (e: Exception) {
            AppLog.add("[悬浮窗] startForeground 失败: ${e.message}")
            Log.e("FloatingBubble", "startForeground failed", e)
            false
        }
    }

    private fun showBubble() {
        AppLog.add("[悬浮窗] 显示悬浮按钮...")
        try {
            val density = resources.displayMetrics.density
            val bubbleSize = (56 * density).toInt()
            AppLog.add("[悬浮窗] 按钮大小: ${bubbleSize}px")
            val params = WindowManager.LayoutParams(
                bubbleSize, bubbleSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = resources.displayMetrics.heightPixels / 3
            }
            bubbleView = BubbleView(this).apply {
                onClick = {
                    AppLog.add("[悬浮窗] 按钮被点击")
                    onBubbleClick()
                }
            }
            windowManager.addView(bubbleView, params)
            AppLog.add("[悬浮窗] 按钮已添加到窗口")
        } catch (e: Exception) {
            AppLog.add("[悬浮窗] 显示按钮失败: ${e.message}")
            Log.e("FloatingBubble", "showBubble failed", e)
        }
    }

    private fun onBubbleClick() {
        if (CaptureState.mediaProjection == null) {
            AppLog.add("[悬浮窗] mediaProjection 为空，请先在 app 中开启悬浮窗时授权截屏权限")
        } else {
            AppLog.add("[悬浮窗] mediaProjection 已就绪，直接截屏")
            captureAndAnalyze()
        }
    }

    private fun captureAndAnalyze() {
        val projection = CaptureState.mediaProjection
        if (projection == null) {
            AppLog.add("[悬浮窗] 截屏失败: mediaProjection 为空")
            return
        }
        AppLog.add("[悬浮窗] 开始截屏...")
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    ScreenCaptureManager.capture(projection, this@FloatingBubbleService)
                }
                if (file != null) {
                    AppLog.add("[悬浮窗] 截屏成功: ${file.name}")
                    AppLog.add("[悬浮窗] 开始分析...")
                    val result = AnalysisEngine.analyze(this@FloatingBubbleService, file)
                    withContext(Dispatchers.Main) {
                        if (result != null && result.chineseMoves.isNotEmpty()) {
                            AppLog.add("[悬浮窗] 分析成功: ${result.chineseMoves[0]}")
                            showResult(result.chineseMoves[0], result.fen)
                        } else {
                            AppLog.add("[悬浮窗] 分析无结果 (result=${result != null}, moves=${result?.chineseMoves?.size ?: 0})")
                        }
                    }
                } else {
                    AppLog.add("[悬浮窗] 截屏失败: ScreenCaptureManager 返回 null")
                }
            } catch (e: Exception) {
                AppLog.add("[悬浮窗] 截屏分析异常: ${e.message}")
                Log.e("FloatingBubble", "captureAndAnalyze failed", e)
                CaptureState.mediaProjection = null
            }
        }
    }

    private fun showResult(move: String, fen: String) {
        AppLog.add("[悬浮窗] 显示结果: $move")
        try {
            resultOverlay?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}
        resultOverlay = ResultOverlayView(this, move, fen) { view ->
            try { windowManager.removeView(view) } catch (_: Exception) {}
            resultOverlay = null
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager.addView(resultOverlay, params)
        } catch (e: Exception) {
            AppLog.add("[悬浮窗] 显示结果失败: ${e.message}")
            Log.e("FloatingBubble", "showResult addView failed", e)
        }
    }
}
