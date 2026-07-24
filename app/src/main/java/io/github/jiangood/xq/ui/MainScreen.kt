package io.github.jiangood.xq.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.jiangood.xq.viewmodel.AnalysisViewModel
import io.github.jiangood.xq.viewmodel.UiState
import androidx.compose.material3.IconButton

@Composable
fun MainScreen(
    viewModel: AnalysisViewModel,
    onRequestProjection: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("象棋支招", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                val buildTime = remember {
                    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    sdf.format(java.util.Date(io.github.jiangood.xq.BuildConfig.BUILD_TIME.toLong()))
                }
                Text(
                    text = "v${io.github.jiangood.xq.BuildConfig.VERSION_NAME} (${io.github.jiangood.xq.BuildConfig.BUILD_TYPE}) · $buildTime",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onRequestProjection,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("启动悬浮窗")
        }

        Spacer(Modifier.height(12.dp))

        when (val s = state) {
            is UiState.Idle -> {
                Text("请启动悬浮窗后截图分析", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is UiState.Analyzing -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("分析中...")
            }
            is UiState.Result -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("分析结果", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                text = "耗时 ${"%.1f".format(s.elapsedMs / 1000.0)}s",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(12.dp))

                        Text("推荐走法", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = s.moves.firstOrNull() ?: "—",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (s.imageDir != null) {
                            Spacer(Modifier.height(12.dp))
                            Text("识别结果", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))

                            ImageDisplay(
                                imagePath = s.imageDir,
                                contentDescription = "可视化标注"
                            )
                        } else if (s.sourceImageDir != null) {
                            Spacer(Modifier.height(12.dp))
                            Text("截图预览", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))

                            ImageDisplay(
                                imagePath = s.sourceImageDir,
                                contentDescription = "截图"
                            )
                        }

                        if (s.validationWarnings.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            s.validationWarnings.forEach { w ->
                                Text(text = "⚠ $w", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            is UiState.Error -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    if (s.sourceImageDir != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("截图预览", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        ImageDisplay(
                            imagePath = s.sourceImageDir,
                            contentDescription = "截图"
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        val allLogs = viewModel.logs.collectAsState().value

        LogCard(title = "日志", logs = allLogs)
    }

    if (viewModel.showNnueWarning.collectAsState().value) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissNnueWarning() },
            icon = { Text("⚠️", fontSize = 28.sp) },
            title = { Text("精简版提示") },
            text = {
                Text("NNUE 权重文件缺失，当前为精简版安装包，无法启动引擎分析。\n\n请安装完整版以获得完整功能。")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissNnueWarning() }) {
                    Text("知道了")
                }
            }
        )
    }
}

@Composable
private fun LogCard(title: String, logs: List<String>) {
    val context = LocalContext.current
    val logScrollState = rememberScrollState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 8.dp)
                )
                if (logs.isNotEmpty()) {
                    Text(
                        text = "${logs.size} 行",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (logs.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .verticalScroll(logScrollState)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = logs.joinToString("\n"),
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(
                        onClick = {
                            val text = logs.joinToString("\n")
                            copyToClipboard(context, text)
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制日志",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("日志", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
}

@Composable
private fun ImageDisplay(imagePath: String, contentDescription: String) {
    var selectedImage by remember { mutableStateOf<Bitmap?>(null) }
    val bitmap = remember(imagePath) { BitmapFactory.decodeFile(imagePath) }
    bitmap?.let { bmp ->
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { selectedImage = bmp }
        )
    }

    selectedImage?.let { bmp ->
        ZoomableImageDialog(
            bitmap = bmp,
            onDismiss = { selectedImage = null }
        )
    }

    if (bitmap == null) {
        Text(
            text = "图片加载失败: $imagePath",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun ZoomableImageDialog(bitmap: Bitmap, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss)
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    }
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = Color.White
                )
            }
        }
    }
}


