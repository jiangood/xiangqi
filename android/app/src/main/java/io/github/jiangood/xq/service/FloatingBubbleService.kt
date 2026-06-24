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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object CaptureState {
    var mediaProjection: MediaProjection? = null
    var pendingCaptureRequest: Boolean = false
}

class FloatingBubbleService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var windowManager: WindowManager
    private var bubbleView: BubbleView? = null
    private var resultOverlay: View? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!startForegroundSafe()) {
            stopSelf()
            return
        }
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "CAPTURE_NOW" -> captureAndAnalyze()
            "STOP" -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
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
                val channel = android.app.NotificationChannel(
                    channelId, "悬浮窗", NotificationManager.IMPORTANCE_LOW
                )
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("中国象棋支招")
                .setContentText("悬浮窗运行中，点击截图分析")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
            startForeground(1, notification)
            true
        } catch (e: Exception) {
            Log.e("FloatingBubble", "startForeground failed", e)
            false
        }
    }

    private fun showBubble() {
        try {
            val density = resources.displayMetrics.density
            val bubbleSize = (56 * density).toInt()
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
                onClick = { onBubbleClick() }
            }
            windowManager.addView(bubbleView, params)
        } catch (e: Exception) {
            Log.e("FloatingBubble", "showBubble failed", e)
        }
    }

    private fun onBubbleClick() {
        if (CaptureState.mediaProjection == null) {
            CaptureState.pendingCaptureRequest = true
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            intent?.action = "REQUEST_CAPTURE"
            startActivity(intent)
        } else {
            captureAndAnalyze()
        }
    }

    private fun captureAndAnalyze() {
        val projection = CaptureState.mediaProjection ?: return
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    ScreenCaptureManager.capture(projection, this@FloatingBubbleService)
                }
                if (file != null) {
                    val result = AnalysisEngine.analyze(this@FloatingBubbleService, file)
                    withContext(Dispatchers.Main) {
                        if (result != null && result.chineseMoves.isNotEmpty()) {
                            showResult(result.chineseMoves[0])
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("FloatingBubble", "captureAndAnalyze failed", e)
                CaptureState.mediaProjection = null
            }
        }
    }

    private fun showResult(move: String) {
        try {
            resultOverlay?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}
        resultOverlay = ResultOverlayView(this, move) { view ->
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
            Log.e("FloatingBubble", "showResult addView failed", e)
        }
    }
}
