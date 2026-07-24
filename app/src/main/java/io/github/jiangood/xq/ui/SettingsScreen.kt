package io.github.jiangood.xq.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.jiangood.xq.BuildConfig
import io.github.jiangood.xq.service.CaptureOverlayService
import io.github.jiangood.xq.settings.SettingsManager
import io.github.jiangood.xq.update.UpdateInfo
import io.github.jiangood.xq.update.UpdateManager
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

private sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object Latest : UpdateStatus
    data class Available(val info: UpdateInfo) : UpdateStatus
    data class Error(val message: String) : UpdateStatus
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var depthDialog by remember { mutableStateOf(false) }
    var threadsDialog by remember { mutableStateOf(false) }

    var depth by remember { mutableStateOf(SettingsManager.getDepth()) }
    var threads by remember { mutableStateOf(SettingsManager.getThreads()) }

    var updateStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Idle) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadFailed by remember { mutableStateOf(false) }

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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            SettingCell(
                title = "搜索深度",
                value = "$depth 层",
                onClick = { depthDialog = true }
            )

            SettingCell(
                title = "线程数",
                value = "$threads 线程",
                onClick = { threadsDialog = true }
            )

            // ── 检查更新 ──
            SettingCell(
                title = "检查更新",
                value = when (updateStatus) {
                    is UpdateStatus.Idle -> "v${BuildConfig.VERSION_NAME}"
                    is UpdateStatus.Checking -> "检查中..."
                    is UpdateStatus.Latest -> "已是最新版本"
                    is UpdateStatus.Available -> "发现新版本"
                    is UpdateStatus.Error -> "检查失败，点击重试"
                },
                valueColor = when (updateStatus) {
                    is UpdateStatus.Available -> MaterialTheme.colorScheme.primary
                    is UpdateStatus.Latest -> MaterialTheme.colorScheme.onSurfaceVariant
                    is UpdateStatus.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                onClick = {
                    if (isDownloading) return@SettingCell
                    updateStatus = UpdateStatus.Checking
                    scope.launch {
                        val result = UpdateManager.checkForUpdate(BuildConfig.VERSION_NAME)
                        result.onSuccess { info ->
                            updateStatus = if (info != null) {
                                UpdateStatus.Available(info)
                            } else {
                                UpdateStatus.Latest
                            }
                        }.onFailure { e ->
                            updateStatus = UpdateStatus.Error(e.message ?: "未知错误")
                        }
                    }
                }
            )

            // ── 上次悬浮窗截图 ──
            val screenshotPath = CaptureOverlayService.lastScreenshotPath
            if (screenshotPath != null) {
                val screenshotFile = File(screenshotPath)
                if (screenshotFile.exists()) {
                    LastScreenshotCard(
                        imagePath = screenshotPath,
                        moveText = CaptureOverlayService.lastAnalysisMove,
                        onOpenImage = {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                screenshotFile
                            )
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "image/jpeg")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try { context.startActivity(intent) } catch (_: Exception) { }
                        },
                        onSaveToGallery = { saveToGallery(context, screenshotPath) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

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

    // ── 更新可用对话框 ──
    val availableInfo = (updateStatus as? UpdateStatus.Available)?.info
    if (availableInfo != null && !isDownloading) {
        AlertDialog(
            onDismissRequest = {
                updateStatus = UpdateStatus.Idle
            },
            title = { Text("发现新版本") },
            text = {
                Column {
                    Text("当前版本：v${BuildConfig.VERSION_NAME}")
                    Text("最新版本：v${availableInfo.latestVersion}")
                    Spacer(Modifier.height(8.dp))
                    if (availableInfo.releaseNotes.isNotBlank()) {
                        Text(
                            text = "更新内容：",
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                        Text(
                            text = availableInfo.releaseNotes,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 8
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    if (availableInfo.assetSize > 0) {
                        val sizeMb = availableInfo.assetSize / (1024.0 * 1024.0)
                        Text(
                            text = "大小：%.1f MB".format(sizeMb),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (downloadFailed) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "⚠ 上次下载失败，已清除缓存。点击「重新下载」重试。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    isDownloading = true
                    downloadFailed = false
                    scope.launch {
                        val file = UpdateManager.downloadApk(
                            context = context,
                            url = availableInfo.downloadUrl,
                            version = availableInfo.latestVersion,
                            onProgress = { pct -> downloadProgress = pct }
                        )
                        if (file != null) {
                            isDownloading = false
                            downloadProgress = null
                            downloadFailed = false
                            updateStatus = UpdateStatus.Idle
                            UpdateManager.installApk(context, file)
                        } else {
                            isDownloading = false
                            downloadProgress = null
                            downloadFailed = true
                            snackbarHostState.showSnackbar("下载失败，请检查网络后重试")
                        }
                    }
                }) {
                    Text(if (downloadFailed) "重新下载" else "下载并安装")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    updateStatus = UpdateStatus.Idle
                }) {
                    Text("稍后")
                }
            }
        )
    }

    // ── 下载进度对话框 ──
    if (isDownloading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("下载更新") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val progress = downloadProgress ?: 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        fontSize = 14.sp
                    )
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
    }

    // ── 已是最新 Snackbar ──
    LaunchedEffect(updateStatus) {
        if (updateStatus is UpdateStatus.Latest) {
            snackbarHostState.showSnackbar("已是最新版本")
            // 稍后恢复为 Idle，方便再次点击
            kotlinx.coroutines.delay(2000)
            updateStatus = UpdateStatus.Idle
        } else if (updateStatus is UpdateStatus.Error) {
            snackbarHostState.showSnackbar(
                (updateStatus as UpdateStatus.Error).message,
                duration = SnackbarDuration.Short
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
                depth = v
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
                threads = v
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

@Composable
private fun LastScreenshotCard(
    imagePath: String,
    moveText: String?,
    onOpenImage: () -> Unit,
    onSaveToGallery: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("上次截图", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (moveText != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "走法: $moveText",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            val bitmap = remember(imagePath) { BitmapFactory.decodeFile(imagePath) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "截图预览",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .clickable(onClick = onOpenImage)
                )
            }
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenImage,
                    modifier = Modifier.weight(1f)
                ) { Text("查看") }
                Button(
                    onClick = onSaveToGallery,
                    modifier = Modifier.weight(1f)
                ) { Text("保存到相册") }
            }
        }
    }
}

private fun saveToGallery(context: Context, imagePath: String) {
    try {
        val file = File(imagePath)
        if (!file.exists()) {
            Toast.makeText(context, "截图文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = BitmapFactory.decodeFile(imagePath) ?: run {
            Toast.makeText(context, "图片解码失败", Toast.LENGTH_SHORT).show()
            return
        }
        val filename = "xiangqi_${System.currentTimeMillis()}.jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/象棋支招")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Toast.makeText(context, "已保存到「相册/象棋支招」", Toast.LENGTH_SHORT).show()
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val dest = File(dir, filename)
            FileOutputStream(dest).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dest))
            context.sendBroadcast(intent)
            Toast.makeText(context, "已保存到 $dest", Toast.LENGTH_SHORT).show()
        }
        bitmap.recycle()
    } catch (e: Exception) {
        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
