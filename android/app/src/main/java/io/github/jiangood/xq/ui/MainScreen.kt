package io.github.jiangood.xq.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.jiangood.xq.util.AppLog
import io.github.jiangood.xq.viewmodel.AnalysisViewModel
import io.github.jiangood.xq.viewmodel.UiState
import androidx.compose.material3.IconButton

@Composable
fun MainScreen(
    viewModel: AnalysisViewModel,
    onPickImage: () -> Unit,
    onToggleFloating: (Boolean) -> Unit,
    onReparseLast: () -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    var floatingEnabled by remember { mutableStateOf(false) }

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
            Text("象棋支招", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("悬浮窗", modifier = Modifier.weight(1f))
            Switch(
                checked = floatingEnabled,
                onCheckedChange = {
                    floatingEnabled = it
                    onToggleFloating(it)
                }
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onPickImage, modifier = Modifier.weight(1f)) {
                Text("选择图片")
            }
            OutlinedButton(onClick = onReparseLast, modifier = Modifier.weight(1f)) {
                Text("解析上次截图")
            }
        }

        Spacer(Modifier.height(12.dp))

        when (val s = state) {
            is UiState.Idle -> {
                Text("请选择棋盘图片开始分析", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    "截屏后也可分享到本app快速分析",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
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

                        if (s.stepPreviews.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("处理过程（共 ${s.stepPreviews.size} 步）", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))

                            var selectedImage by remember { mutableStateOf<Bitmap?>(null) }

                            val stepNames = listOf(
                                "原图", "中心裁剪", "灰度图", "Canny 边缘检测",
                                "轮廓检测", "棋盘定位", "棋盘裁剪", "二值化",
                                "水平线检测", "垂直线检测", "楚河汉界检测", "网格红线标注",
                                "精裁棋盘", "YOLO 原始检测", "颜色修正", "棋子识别",
                                "棋子归位", "FEN 识别", "棋盘布局", "最佳走法"
                            )

                            s.stepPreviews.toSortedMap().forEach { (step, path) ->
                                val label = stepNames.getOrNull(step - 1) ?: "步骤 $step"
                                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                                    Text(
                                        text = "步 $step: $label",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    val bitmap = remember(path) { BitmapFactory.decodeFile(path) }
                                    bitmap?.let { bmp ->
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = label,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { selectedImage = bmp }
                                        )
                                    }
                                }
                            }

                            selectedImage?.let { bmp ->
                                ZoomableImageDialog(
                                    bitmap = bmp,
                                    onDismiss = { selectedImage = null }
                                )
                            }
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
                Text(s.message, color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(8.dp))

        val allLogs = viewModel.logs.collectAsState().value

        LogCard(title = "日志", logs = allLogs, autoExpand = state is UiState.Error)
    }
}

@Composable
private fun LogCard(title: String, logs: List<String>, autoExpand: Boolean = false) {
    var expanded by remember { mutableStateOf(autoExpand) }
    LaunchedEffect(autoExpand) { if (autoExpand) expanded = true }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (expanded) "▼ $title (${logs.size})" else "▶ $title (${logs.size})",
                        fontWeight = FontWeight.Medium
                    )
                }
                IconButton(
                    onClick = { AppLog.clear() },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "清空日志",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            AnimatedVisibility(visible = expanded && logs.isNotEmpty()) {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        logs.forEach { line ->
                            Text(
                                text = line,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
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


