package io.github.jiangood.xq.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import io.github.jiangood.xq.analysis.AnalysisEngine
import io.github.jiangood.xq.MainActivity
import io.github.jiangood.xq.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object CaptureState {
    var mediaProjection: MediaProjection? = null
}

class FloatingBubbleService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var windowManager: WindowManager
    private var unifiedView: UnifiedBubbleView? = null
    private var isAnalyzing = false
    private val grantStore by lazy { ScreenCaptureGrantStore(this) }

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
        showUnifiedView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.add("[悬浮窗] onStartCommand action=${intent?.action ?: "无"}")
        when (intent?.action) {
            "CAPTURE_NOW" -> {
                AppLog.add("[悬浮窗] 收到截屏指令")
                captureAndAnalyze()
            }
            "SET_MEDIA_PROJECTION" -> {
                val resultCode = intent.getIntExtra("result_code", -1)
                val data = intent.getParcelableExtra("result_data", Intent::class.java)
                if (data != null) {
                    AppLog.add("[悬浮窗] 服务内创建 MediaProjection")
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val projection = mpm.getMediaProjection(resultCode, data)
                    projection.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            AppLog.add("[悬浮窗] mediaProjection 停止，清理状态")
                            CaptureState.mediaProjection = null
                            ScreenCaptureManager.release()
                        }
                    }, null)
                    CaptureState.mediaProjection = projection
                    ScreenCaptureManager.init(projection, this)
                    AppLog.add("[悬浮窗] mediaProjection 已保存")
                }
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
        ScreenCaptureManager.release()
        try {
            unifiedView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startForegroundSafe(): Boolean {
        return try {
            val channelId = "floating_bubble_channel"
            AppLog.add("[悬浮窗] 创建通知渠道...")
            val channel = android.app.NotificationChannel(
                channelId, "悬浮窗", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
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

    private fun showUnifiedView() {
        AppLog.add("[悬浮窗] 显示合并悬浮窗...")
        try {
            val density = resources.displayMetrics.density
            val width = (100 * density).toInt()
            val height = (100 * density).toInt()
            val params = WindowManager.LayoutParams(
                width, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val metrics = DisplayMetrics()
                windowManager.defaultDisplay.getRealMetrics(metrics)
                val rightMarginDp = 80f
                val bottomMarginDp = 120f
                x = metrics.widthPixels - width - (rightMarginDp * density).toInt()
                y = metrics.heightPixels - height - (bottomMarginDp * density).toInt()
            }
            unifiedView = UnifiedBubbleView(this).apply {
                onClick = {
                    AppLog.add("[悬浮窗] 合并按钮被点击")
                    onUnifiedClick()
                }
            }
            windowManager.addView(unifiedView, params)
            AppLog.add("[悬浮窗] 合并悬浮窗已添加到窗口")
        } catch (e: Exception) {
            AppLog.add("[悬浮窗] 显示合并悬浮窗失败: ${e.message}")
            Log.e("FloatingBubble", "showUnifiedView failed", e)
        }
    }

    private fun onUnifiedClick() {
        if (isAnalyzing) {
            AppLog.add("[悬浮窗] 正在分析中，忽略本次点击")
            return
        }
        if (CaptureState.mediaProjection == null) {
            AppLog.add("[悬浮窗] mediaProjection 为空，尝试从持久化 grant 恢复")
            unifiedView?.updateState(UnifiedBubbleView.State.IDLE)
            restoreProjectionOrRequest()
        } else {
            AppLog.add("[悬浮窗] mediaProjection 已就绪，直接截屏")
            unifiedView?.updateState(UnifiedBubbleView.State.PROCESSING)
            captureAndAnalyze()
        }
    }

    private fun restoreProjectionOrRequest() {
        val grant = grantStore.loadGrant()
        if (grant != null) {
            val (resultCode, data) = grant
            try {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = mpm.getMediaProjection(resultCode, data)
                if (projection != null) {
                    projection.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            AppLog.add("[悬浮窗] mediaProjection 停止，清理状态")
                            CaptureState.mediaProjection = null
                            ScreenCaptureManager.release()
                        }
                    }, null)
                    CaptureState.mediaProjection = projection
                    ScreenCaptureManager.init(projection, this)
                    AppLog.add("[悬浮窗] 从持久化 grant 重建 MediaProjection 成功")
                    unifiedView?.updateState(UnifiedBubbleView.State.PROCESSING)
                    captureAndAnalyze()
                    return
                }
            } catch (e: Exception) {
                AppLog.add("[悬浮窗] 从持久化 grant 重建失败: ${e.message}")
                grantStore.clearGrant()
            }
        }
        AppLog.add("[悬浮窗] 无法从持久化 grant 重建，需要重新授权截屏权限")
        requestScreenCapturePermission()
    }

    private fun requestScreenCapturePermission() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "REQUEST_SCREEN_CAPTURE"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun captureAndAnalyze() {
        val projection = CaptureState.mediaProjection
        if (projection == null) {
            AppLog.add("[悬浮窗] 截屏失败: mediaProjection 为空")
            unifiedView?.updateState(UnifiedBubbleView.State.IDLE)
            return
        }
        if (!ScreenCaptureManager.isInitialized()) {
            AppLog.add("[悬浮窗] ScreenCaptureManager 未初始化，重新 init")
            if (!ScreenCaptureManager.init(projection, this)) {
                AppLog.add("[悬浮窗] ScreenCaptureManager.init 失败")
                unifiedView?.updateState(UnifiedBubbleView.State.FAILED, error = "初始化失败")
                return
            }
        }
        isAnalyzing = true
        AppLog.add("[悬浮窗] 开始截屏...")
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    ScreenCaptureManager.capture()
                }
                if (file != null) {
                    AppLog.add("[悬浮窗] 截屏成功: ${file.name} (${file.length()} bytes)")
                    AppLog.add("[悬浮窗] 开始分析...")
                    val result = AnalysisEngine.analyze(file)
                    withContext(Dispatchers.Main) {
                        if (result != null && result.chineseMoves.isNotEmpty()) {
                            AppLog.add("[悬浮窗] 分析成功, FEN=${result.fen}")
                            AppLog.add("[悬浮窗] 原始走法: ${result.standardMoves.joinToString(", ")}")
                            AppLog.add("[悬浮窗] 中文走法: ${result.chineseMoves.joinToString(", ")}")
                            AppLog.add("[悬浮窗] 显示: ${result.chineseMoves[0]}")
                            unifiedView?.updateState(UnifiedBubbleView.State.SUCCESS, move = result.chineseMoves[0])
                            AnalysisHistoryStore.addEntry(this@FloatingBubbleService, HistoryEntry(result.fen, result.chineseMoves[0], result.standardMoves[0], System.currentTimeMillis()))
                            AppLog.add("[悬浮窗] 已保存历史记录")
                            delayAutoIdle()
                        } else {
                            AppLog.add("[悬浮窗] 分析无结果 (result=${result != null}, chineseMoves=${result?.chineseMoves?.size ?: 0}, standardMoves=${result?.standardMoves?.size ?: 0})")
                            unifiedView?.updateState(UnifiedBubbleView.State.FAILED, error = "无结果")
                            delayAutoIdle()
                        }
                    }
                } else {
                    AppLog.add("[悬浮窗] 截屏失败: ScreenCaptureManager 返回 null")
                    unifiedView?.updateState(UnifiedBubbleView.State.FAILED, error = "截屏失败")
                    delayAutoIdle()
                    // MediaProjection may have been stopped, clear it and request new permission
                    CaptureState.mediaProjection = null
                    withContext(Dispatchers.Main) {
                        requestScreenCapturePermission()
                    }
                }
            } catch (e: Exception) {
                AppLog.add("[悬浮窗] 截屏分析异常: ${e.message}")
                Log.e("FloatingBubble", "captureAndAnalyze failed", e)
                unifiedView?.updateState(UnifiedBubbleView.State.FAILED, error = e.message)
                delayAutoIdle()
            } finally {
                isAnalyzing = false
            }
        }
    }

    private var idleJob: kotlinx.coroutines.Job? = null

    private fun delayAutoIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(3000)
            unifiedView?.updateState(UnifiedBubbleView.State.IDLE)
        }
    }
}