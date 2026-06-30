package io.github.jiangood.xq

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
                        onOpenCalibration = { showCalibration = true }
                    )
                }
                else -> {
                    MainScreen(
                        viewModel = viewModel,
                        onPickImage = {
                            pickMedia.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
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
                        },
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
}
