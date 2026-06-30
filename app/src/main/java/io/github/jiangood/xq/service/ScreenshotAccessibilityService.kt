package io.github.jiangood.xq.service

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import io.github.jiangood.xq.MainActivity
import io.github.jiangood.xq.R
import io.github.jiangood.xq.analysis.AnalysisEngine
import io.github.jiangood.xq.util.AppLog
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

class ScreenshotAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_CAPTURE = "io.github.jiangood.xq.action.CAPTURE"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "xq_screenshot"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isAnalyzing = false
    private var notiBuilder: NotificationCompat.Builder? = null
    private var idleJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.add("[无障碍] onCreate")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("就绪"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CAPTURE) {
            onCaptureClick()
        }
        return START_STICKY
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
        idleJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            "截图分析",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "象棋支招截图分析服务"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): android.app.Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val captureIntent = Intent(this, ScreenshotAccessibilityService::class.java).apply {
            action = ACTION_CAPTURE
        }
        val capturePendingIntent = PendingIntent.getForegroundService(
            this, 1, captureIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        notiBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("象棋支招")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapPendingIntent)
            .addAction(android.R.drawable.ic_menu_camera, "截图分析", capturePendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        return notiBuilder!!.build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        notiBuilder?.let {
            it.setContentText(text)
            nm.notify(NOTIFICATION_ID, it.build())
        }
    }

    private fun onCaptureClick() {
        if (isAnalyzing) {
            AppLog.add("[无障碍] 正在分析中，忽略本次点击")
            return
        }
        AppLog.add("[无障碍] 按钮被点击，开始截图分析")
        updateNotification("处理中...")
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
                            updateNotification("推荐: ${result.chineseMoves[0]}")
                            delayAutoIdle()
                        } else {
                            AppLog.add("[无障碍] 分析无结果")
                            updateNotification("无结果")
                            delayAutoIdle()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        AppLog.add("[无障碍] 截屏失败")
                        updateNotification("截屏失败")
                        delayAutoIdle()
                    }
                }
            } catch (e: Exception) {
                AppLog.add("[无障碍] 截图分析异常: ${e.message}")
                withContext(Dispatchers.Main) {
                    updateNotification("出错: ${e.message}")
                    delayAutoIdle()
                }
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

    private fun delayAutoIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(5000)
            updateNotification("就绪")
        }
    }
}
