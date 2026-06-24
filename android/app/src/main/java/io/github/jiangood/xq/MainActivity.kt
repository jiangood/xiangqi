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
import io.github.jiangood.xq.analysis.AnalysisEngine
import io.github.jiangood.xq.service.CaptureState
import io.github.jiangood.xq.service.FloatingBubbleService
import io.github.jiangood.xq.ui.MainScreen
import io.github.jiangood.xq.viewmodel.AnalysisViewModel
import io.github.jiangood.xq.viewmodel.UiState

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
            checkAndRequestNotificationPermission()
        } else {
            Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startFloatingService()
            }
        } else {
            startFloatingService()
        }
    }
    }
    // Removed duplicate notificationPermissionLauncher declaration

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(result.resultCode, result.data!!)
            projection.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() {
                    CaptureState.mediaProjection = null
                }
            }, null)
            CaptureState.mediaProjection = projection
            if (CaptureState.pendingCaptureRequest) {
                CaptureState.pendingCaptureRequest = false
                Intent(this, FloatingBubbleService::class.java).also {
                    it.action = "CAPTURE_NOW"
                    startService(it)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.initOpenCV(this)
        viewModel.initAnalysisEngine(this)

        handleShareIntent(intent)
        handleRequestCapture(intent)

        setContent {
            MainScreen(
                viewModel = viewModel,
                onPickImage = { pickMedia.launch(ActivityResultContracts.PickVisualMedia.ImageOnly.let { androidx.activity.result.PickVisualMediaRequest(it) }) },
                onToggleFloating = { enabled ->
                    if (enabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        overlayPermissionLauncher.launch(intent)
                    } else {
                        checkAndRequestNotificationPermission()
                    }
                    } else {
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
