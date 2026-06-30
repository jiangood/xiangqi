package io.github.jiangood.xq.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jiangood.xq.BuildConfig
import io.github.jiangood.xq.settings.CalibrationManager
import io.github.jiangood.xq.settings.SettingsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCalibration: () -> Unit = {},
    onOpenAccessibility: () -> Unit = {}
) {
    val context = LocalContext.current

    var depthDialog by remember { mutableStateOf(false) }
    var threadsDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            SettingCell(
                title = "搜索深度",
                value = "${SettingsManager.getDepth()} 层",
                onClick = { depthDialog = true }
            )

            SettingCell(
                title = "线程数",
                value = "${SettingsManager.getThreads()} 线程",
                onClick = { threadsDialog = true }
            )

            val calibrated = CalibrationManager.isCalibrated(context)
            SettingCell(
                title = "棋盘棋子校准",
                value = if (calibrated) "已校准" else "未校准",
                valueColor = if (calibrated) androidx.compose.ui.graphics.Color(0xFF4CAF50)
                            else MaterialTheme.colorScheme.error,
                onClick = onOpenCalibration
            )

            SettingCell(
                title = "无障碍截图",
                value = "开启后通知栏出现截图按钮",
                onClick = onOpenAccessibility
            )

            Spacer(Modifier.weight(1f))

            HorizontalDivider()
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                modifier = Modifier
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/jiangood/xiangqi/releases"))
                        context.startActivity(intent)
                    }
                    .padding(vertical = 12.dp)
                    .fillMaxWidth(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.Underline
            )
        }
    }

    if (depthDialog) {
        SimpleNumberDialog(
            title = "搜索深度",
            current = SettingsManager.getDepth(),
            min = 5, max = 30,
            onDismiss = { depthDialog = false },
            onConfirm = { v ->
                SettingsManager.setDepth(v)
                depthDialog = false
            }
        )
    }

    if (threadsDialog) {
        SimpleNumberDialog(
            title = "线程数",
            current = SettingsManager.getThreads(),
            min = 1, max = 8,
            onDismiss = { threadsDialog = false },
            onConfirm = { v ->
                SettingsManager.setThreads(v)
                threadsDialog = false
            }
        )
    }
}

@Composable
private fun SettingCell(
    title: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                )
                Text(
                    text = value,
                    fontSize = 13.sp,
                    color = valueColor
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SimpleNumberDialog(
    title: String,
    current: Int,
    min: Int, max: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember { mutableStateOf(current.toString()) }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v ->
                        text = v
                        error = v.toIntOrNull()?.let { it in min..max } != true
                    },
                    label = { Text("范围 $min ~ $max") },
                    isError = error,
                    supportingText = if (error) {{ Text("请输入 $min ~ $max 之间的整数") }} else null,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    text.toIntOrNull()?.let { v ->
                        if (v in min..max) {
                            onConfirm(v)
                        }
                    }
                },
                enabled = !error && text.isNotBlank()
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
