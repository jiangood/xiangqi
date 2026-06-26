package io.github.jiangood.xq.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jiangood.xq.BuildConfig
import io.github.jiangood.xq.viewmodel.AnalysisViewModel
import io.github.jiangood.xq.viewmodel.UiState

@Composable
fun MainScreen(
    viewModel: AnalysisViewModel,
    onPickImage: () -> Unit,
    onToggleFloating: (Boolean) -> Unit
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
        Text("象棋支招", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(16.dp))

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

        Spacer(Modifier.height(12.dp))

        Button(onClick = onPickImage) {
            Text("选择图片")
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
                        Text("分析结果", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(12.dp))

                        Text("推荐走法", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = s.moves.getOrElse(s.currentMoveIndex) { "—" },
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (s.moves.size > 1) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = {
                                    if (s.currentMoveIndex > 0) viewModel.selectMove(s.currentMoveIndex - 1)
                                }) { Text("◀") }
                                Spacer(Modifier.weight(1f))
                                Text("第 ${s.currentMoveIndex + 1}/${s.moves.size} 条")
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = {
                                    if (s.currentMoveIndex < s.moves.size - 1) viewModel.selectMove(s.currentMoveIndex + 1)
                                }) { Text("▶") }
                            }
                        }

                        s.stepPreviews[6]?.let { bmp ->
                            Spacer(Modifier.height(12.dp))
                            Text("走法预览", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Spacer(Modifier.height(4.dp))
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "走法预览",
                                modifier = Modifier.fillMaxWidth()
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
                Text(s.message, color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(16.dp))
        val context = LocalContext.current
        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/jiangood/xiangqi/releases/latest"))
                context.startActivity(intent)
            }
        )

        RuntimeLogCard(logs = viewModel.logs.collectAsState().value, isError = state is UiState.Error)
    }
}

@Composable
private fun RuntimeLogCard(logs: List<String>, isError: Boolean) {
    var expanded by remember { mutableStateOf(isError) }
    LaunchedEffect(isError) { if (isError) expanded = true }

    Spacer(Modifier.height(8.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            ) {
                Text(
                    text = if (expanded) "▼ 运行日志 (${logs.size})" else "▶ 运行日志 (${logs.size})",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Medium
                )
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


