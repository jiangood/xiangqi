package io.github.jiangood.xq.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import io.github.jiangood.xq.MainActivity
import io.github.jiangood.xq.R
import io.github.jiangood.xq.analysis.AnalysisEngine
import io.github.jiangood.xq.analysis.AnalysisEventBus
import io.github.jiangood.xq.analysis.AnalysisResult
import io.github.jiangood.xq.util.AppLog
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CaptureOverlayService : Service() {

    companion object {
        const val ACTION_CAPTURE = "io.github.jiangood.xq.action.CAPTURE"
        const val ACTION_SET_PROJECTION = "io.github.jiangood.xq.action.SET_PROJECTION"
        const val ACTION_REFRESH_OVERLAY = "io.github.jiangood.xq.action.REFRESH_OVERLAY"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "xq_capture"

        /** 最近一次截图的文件路径（用于在主界面展示和保存） */
        @JvmStatic var lastScreenshotPath: String? = null
        /** 最近一次分析出的走法文字 */
        @JvmStatic var lastAnalysisMove: String? = null
        /** 最近一次完整的分析结果 */
        @JvmStatic var lastAnalysisResult: AnalysisResult? = null
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isAnalyzing = false
    private var notiBuilder: NotificationCompat.Builder? = null
    private var idleJob: Job? = null

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private lateinit var iconButton: TextView
    private lateinit var statusText: TextView
    private lateinit var closeButton: TextView
    private var isResultShowing = false

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: android.os.HandlerThread? = null
    private var captureHandler: android.os.Handler? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.add("[悬浮窗] onCreate")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("就绪"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE -> onCaptureClick()
            ACTION_REFRESH_OVERLAY -> tryCreateOverlay()
            ACTION_SET_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA) as? Intent
                }
                if (resultCode == Activity.RESULT_OK && data != null) {
                    setupMediaProjection(resultCode, data)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AppLog.add("[悬浮窗] onDestroy")
        destroyOverlay()
        destroyMediaProjection()
        scope.cancel()
        idleJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ── MediaProjection ──

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection?.stop()
        mediaProjection = mpm.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            AppLog.add("[悬浮窗] 获取投影失败")
            return
        }
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                AppLog.add("[悬浮窗] 投影已停止")
                destroyMediaProjection()
            }
        }, null)
        AppLog.add("[悬浮窗] 投影权限已获取")
        createCaptureResources()
        tryCreateOverlay()
    }

    private fun createCaptureResources() {
        val mp = mediaProjection ?: return
        val realMetrics = getRealDisplayMetrics()
        val w = realMetrics.widthPixels
        val h = realMetrics.heightPixels

        captureThread = android.os.HandlerThread("xq-capture").apply { start() }
        captureHandler = android.os.Handler(captureThread!!.looper)

        virtualDisplay = mp.createVirtualDisplay(
            "xq-capture", w, h, realMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null, null, null
        )
        AppLog.add("[悬浮窗] 截图资源已创建 ${w}x${h}")
    }

    private fun destroyCaptureResources() {
        virtualDisplay?.release()
        virtualDisplay = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
    }

    private fun destroyMediaProjection() {
        destroyOverlay()
        destroyCaptureResources()
        mediaProjection?.stop()
        mediaProjection = null
    }

    // ── Overlay ──

    private fun tryCreateOverlay() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) {
            AppLog.add("[悬浮窗] 无悬浮窗权限")
            return
        }
        createOverlay()
    }

    private fun createOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val wm = windowManager ?: return
        val screenWidth = resources.displayMetrics.widthPixels

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - dpToPx(72)
            y = dpToPx(50)
        }

        overlayView = buildOverlayView()
        wm.addView(overlayView, overlayParams)
        AppLog.add("[悬浮窗] 已创建")
    }

    private fun destroyOverlay() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: IllegalArgumentException) {}
        }
        overlayView = null
        windowManager = null
        AppLog.add("[悬浮窗] 已销毁")
    }

    private fun buildOverlayView(): View {
        val ctx = this

        iconButton = TextView(ctx).apply {
            text = "支"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            val size = dpToPx(44)
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#C62828"))
            }
        }

        statusText = TextView(ctx).apply {
            text = "就绪"
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.parseColor("#212121"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dpToPx(8)
            lp.marginEnd = dpToPx(4)
            layoutParams = lp
        }

        closeButton = TextView(ctx).apply {
            text = "✕"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9E9E9E"))
            val size = dpToPx(28)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            addView(iconButton)
            addView(statusText)
            addView(closeButton)

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(26f, 26f, 26f, 26f, 26f, 26f, 26f, 26f)
                setColor(Color.parseColor("#FFFFFF"))
                setStroke(dpToPx(1), Color.parseColor("#E0E0E0"))
            }
            background = bg
            elevation = dpToPx(4).toFloat()
        }

        iconButton.setOnClickListener { onCaptureClick() }
        closeButton.setOnClickListener { hideResult() }

        return root
    }

    private fun showResult(text: String) {
        statusText.text = text
        closeButton.visibility = View.VISIBLE
        isResultShowing = true
    }

    private fun hideResult() {
        idleJob?.cancel()
        statusText.text = "就绪"
        closeButton.visibility = View.GONE
        isResultShowing = false
        updateNotification("就绪")
    }

    // ── Capture & Analyze ──

    private fun onCaptureClick() {
        if (isAnalyzing) return
        if (mediaProjection == null) {
            showResult("未授权投影")
            updateNotification("未授权投影")
            return
        }
        AppLog.add("[悬浮窗] 开始截图分析")
        showResult("分析中...")
        updateNotification("分析中...")
        captureAndAnalyze()
    }

    private fun captureAndAnalyze() {
        isAnalyzing = true
        scope.launch {
            try {
                AppLog.add("[悬浮窗] 开始截屏...")
                val file = captureScreenshot()

                if (file != null) {
                    AppLog.add("[悬浮窗] 截屏成功: ${file.name} (${file.length()} bytes)")

                    // 持久化保存截图到 filesDir/xiangqi_screenshots/
                    saveScreenshotPersistently(file)

                    // 直接在服务中分析，不打开 MainActivity
                    val result = withContext(Dispatchers.IO) {
                        AnalysisEngine.awaitInitialized()
                        AnalysisEngine.analyze(file)
                    }

                    withContext(Dispatchers.Main) {
                        if (result != null && result.chineseMoves.isNotEmpty()) {
                            val topMove = result.chineseMoves.first()
                            lastAnalysisMove = topMove
                            lastAnalysisResult = result
                            showResult(topMove)
                            updateNotification("推荐走法: $topMove")
                        } else if (result != null) {
                            lastAnalysisResult = result
                            showResult("分析完成")
                            updateNotification("分析完成")
                        } else {
                            showResult("分析失败")
                            updateNotification("分析失败")
                        }
                        // 通过 EventBus 通知主界面
                        if (result != null) {
                            AnalysisEventBus.publish(AnalysisEventBus.BusEvent.Result(result, 0L))
                        } else {
                            AnalysisEventBus.publish(AnalysisEventBus.BusEvent.Error("分析失败", file?.absolutePath))
                        }
                        delayAutoIdle()
                    }
                } else {
                    AppLog.add("[悬浮窗] 截屏失败: 未获得截图文件")
                    withContext(Dispatchers.Main) {
                        showResult("截屏失败")
                        updateNotification("截屏失败")
                        delayAutoIdle()
                    }
                }
            } catch (e: Exception) {
                AppLog.add("[悬浮窗] 截屏异常: ${e.message}")
                withContext(Dispatchers.Main) {
                    showResult("截屏出错")
                    updateNotification("截屏出错: ${e.message}")
                    delayAutoIdle()
                }
            } finally {
                isAnalyzing = false
            }
        }
    }

    private suspend fun captureScreenshot(): File? = withContext(Dispatchers.IO) {
        val mp = mediaProjection ?: return@withContext null
        val vd = virtualDisplay ?: return@withContext null
        val handler = captureHandler ?: return@withContext null
        val realMetrics = getRealDisplayMetrics()

        val reader = ImageReader.newInstance(
            realMetrics.widthPixels, realMetrics.heightPixels,
            PixelFormat.RGBA_8888, 2
        )
        val latch = CountDownLatch(1)
        var imageFile: File? = null
        var frameCount = 0
        var done = false

        reader.setOnImageAvailableListener({
            if (done) return@setOnImageAvailableListener
            try {
                // 跳过前 1 帧 — VirtualDisplay 挂载 surface 后首帧可能是空帧
                frameCount++
                if (frameCount <= 1) {
                    reader.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }

                // acquireLatestImage: 获取最新的可用帧，自动释放之前的帧
                // 不会抛出 maxImages 异常 — 内部会 close 旧帧
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

                if (done) {
                    img.close()
                    return@setOnImageAvailableListener
                }

                try {
                    val bitmap = imageToBitmap(img)
                    val file = File(cacheDir, "screenshot_${System.nanoTime()}.jpg")
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    bitmap.recycle()
                    imageFile = file
                } finally {
                    img.close()
                }

                done = true
                latch.countDown()
            } catch (e: Exception) {
                AppLog.add("[悬浮窗] listener 异常: ${e.message} — ${e.javaClass.simpleName}")
                if (!done) { done = true; latch.countDown() }
            }
        }, handler)

        vd.surface = reader.surface

        if (!latch.await(5, TimeUnit.SECONDS)) {
            AppLog.add("[悬浮窗] 截屏超时")
        }

        // 将清理逻辑投递到 handler 线程，确保与 listener 没有竞态
        val cleanupDone = CountDownLatch(1)
        handler.post {
            reader.setOnImageAvailableListener(null, null)
            vd.surface = null
            reader.close()
            cleanupDone.countDown()
        }
        cleanupDone.await(2, TimeUnit.SECONDS)
        imageFile
    }

    private fun imageToBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = img.width
        val height = img.height
        buffer.rewind()
        val pixels = IntArray(width * height)
        val row = ByteArray(pixelStride * width)
        var idx = 0
        for (y in 0 until height) {
            val pos = y * rowStride
            buffer.position(pos)
            buffer.get(row, 0, pixelStride * width)
            for (x in 0 until width) {
                val r = row[x * 4].toInt() and 0xFF
                val g = row[x * 4 + 1].toInt() and 0xFF
                val b = row[x * 4 + 2].toInt() and 0xFF
                val a = row[x * 4 + 3].toInt() and 0xFF
                pixels[idx++] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    // ── Notification ──

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "截图分析",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "象棋支招截图分析服务"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        notiBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("象棋支招")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapPendingIntent)
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

    // ── Helpers ──

    /**
     * 将截图拷贝到持久化目录 filesDir/xiangqi_screenshots/，
     * 并更新 lastScreenshotPath。自动清理旧截图，只保留最新一张。
     */
    private fun saveScreenshotPersistently(tempFile: File) {
        try {
            val dir = File(filesDir, "xiangqi_screenshots")
            if (!dir.exists()) dir.mkdirs()

            // 清理旧的截图文件
            dir.listFiles()?.forEach { it.delete() }

            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val dest = File(dir, "screenshot_${ts}.jpg")
            tempFile.copyTo(dest, overwrite = true)
            lastScreenshotPath = dest.absolutePath
            AppLog.add("[悬浮窗] 截图已持久化: ${dest.absolutePath}")
        } catch (e: Exception) {
            AppLog.add("[悬浮窗] 持久化截图失败: ${e.message}")
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun getRealDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
        wm?.defaultDisplay?.getRealMetrics(metrics)
        // fallback: 如果取不到则使用 resources.displayMetrics
        if (metrics.widthPixels == 0 || metrics.heightPixels == 0) {
            metrics.setTo(resources.displayMetrics)
        }
        return metrics
    }

    private fun delayAutoIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(30000)
            hideResult()
        }
    }
}
