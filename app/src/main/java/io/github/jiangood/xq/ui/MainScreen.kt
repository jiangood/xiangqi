package io.github.jiangood.xq.ui

import android.content.ContentValues
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import io.github.jiangood.xq.data.AnalysisRecord
import io.github.jiangood.xq.viewmodel.AnalysisViewModel
import io.github.jiangood.xq.viewmodel.UiState

@Composable
fun MainScreen(
    viewModel: AnalysisViewModel,
    onRequestProjection: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val history by viewModel.history.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
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

        if (state is UiState.Analyzing) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text("分析中...")
            Spacer(Modifier.height(12.dp))
        }

        if (history.isEmpty() && state !is UiState.Analyzing) {
            Text(
                text = "暂无分析记录\n请启动悬浮窗后截图分析",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val listState = rememberLazyListState()
            val historySize by remember { derivedStateOf { history.size } }
            LaunchedEffect(historySize) {
                if (history.isNotEmpty()) {
                    listState.animateScrollToItem(0)
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                state = listState
            ) {
                items(history, key = { it.id }) { record ->
                    HistoryCard(record = record)
                }
            }
        }
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
                TextButton(onClick = { viewModel.dismissNnueWarning() }) { Text("知道了") }
            }
        )
    }
}

@Composable
private fun HistoryCard(record: AnalysisRecord) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val bmp = record.screenshotPath?.let { path ->
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(path, opts)
        }
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(60.dp, 60.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(formatTimestamp(record.timestamp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    if (record.status == "success") {
                        Text("走法: ${record.move ?: "—"}", fontSize = 14.sp, color = Color(0xFFC62828))
                        Spacer(Modifier.height(2.dp))
                        Text("棋子: ${record.pieceCount ?: 0} | 耗时: ${record.elapsedMs?.div(1000f)?.let { "%.1f".format(it) } ?: "—"}s", fontSize = 12.sp)
                    } else {
                        Text("❌ ${record.errorMessage ?: "失败"}", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开"
                    )
                }
            }
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                ExpandedContent(record = record)
            }
        }
    }
}

@Composable
private fun ExpandedContent(record: AnalysisRecord) {
    val context = LocalContext.current

    if (record.status == "success" && record.fen != null) {
        Text("FEN: ${record.fen}", fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        Spacer(Modifier.height(4.dp))
    }

    val boardLines = record.logs.lines().filter { it.contains("row") || it.contains("  row") }
    if (boardLines.isNotEmpty()) {
        Text("棋盘:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
        boardLines.forEach { line ->
            Text(line.trim(), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        Spacer(Modifier.height(8.dp))
    }

    val vizBmp = record.visualizationPath?.let { BitmapFactory.decodeFile(it) }
    val srcBmp = record.screenshotPath?.let { BitmapFactory.decodeFile(it) }
    val displayBmp = vizBmp ?: srcBmp
    if (displayBmp != null) {
        Image(
            bitmap = displayBmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { copyFullResult(record, context) }) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("复制结果", fontSize = 12.sp)
        }
        if (record.screenshotPath != null) {
            OutlinedButton(onClick = { saveImageToGallery(context, record.screenshotPath, "截图") }) {
                Text("下载截图", fontSize = 12.sp)
            }
        }
        if (record.visualizationPath != null) {
            OutlinedButton(onClick = { saveImageToGallery(context, record.visualizationPath, "标注图") }) {
                Text("下载标注图", fontSize = 12.sp)
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    val logs = record.logs.lines()
    if (logs.isNotEmpty()) {
        Text("日志:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape = MaterialTheme.shapes.small)
                .padding(8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(logs.joinToString("\n"), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}

private fun copyFullResult(record: AnalysisRecord, context: Context) {
    val sb = StringBuilder()
    sb.appendLine("===== 象棋分析结果 =====")
    sb.appendLine("时间: ${formatTimestamp(record.timestamp)}")
    if (record.status == "success") {
        sb.appendLine("状态: ✅ 成功")
        sb.appendLine("推荐走法: ${record.move ?: "—"}")
        sb.appendLine("FEN: ${record.fen ?: "—"}")
        sb.appendLine("棋子数: ${record.pieceCount ?: 0}")
        sb.appendLine("总耗时: ${record.elapsedMs?.div(1000f)?.let { "%.1f".format(it) } ?: "—"}s")
    } else {
        sb.appendLine("状态: ❌ 失败")
        sb.appendLine("错误: ${record.errorMessage ?: "—"}")
    }
    sb.appendLine()
    sb.appendLine("--- 日志 ---")
    sb.append(record.logs)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("象棋分析结果", sb.toString()))
    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
}

private fun saveImageToGallery(context: Context, path: String, label: String) {
    try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "xiangqi_${label}_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/象棋支招")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                BitmapFactory.decodeFile(path)?.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            Toast.makeText(context, "${label}已保存到相册", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun formatTimestamp(ts: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ts))
}
