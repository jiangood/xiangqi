package io.github.jiangood.xq.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jiangood.xq.viewmodel.AnalysisViewModel
import io.github.jiangood.xq.viewmodel.UiState

@Composable
fun MainScreen(
    viewModel: AnalysisViewModel,
    onPickImage: () -> Unit
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
        Text("中国象棋支招", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(16.dp))

        Button(onClick = onPickImage) {
            Text("选择图片")
        }

        Spacer(Modifier.height(12.dp))

        when (val s = state) {
            is UiState.Idle -> {
                Text("请选择棋盘图片开始分析", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is UiState.Analyzing -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("分析中...")
            }
            is UiState.Result -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("推荐走法", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = s.moves.getOrElse(s.currentMoveIndex) { "—" },
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        if (s.moves.size > 1) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    if (s.currentMoveIndex > 0) viewModel.selectMove(s.currentMoveIndex - 1)
                                }) { Text("◀") }
                                Text("第 ${s.currentMoveIndex + 1}/${s.moves.size} 条")
                                TextButton(onClick = {
                                    if (s.currentMoveIndex < s.moves.size - 1) viewModel.selectMove(s.currentMoveIndex + 1)
                                }) { Text("▶") }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text("分析详情", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))

                StepPreviewCard(title = "棋子识别", bitmap = s.stepPreviews[3])
                Spacer(Modifier.height(8.dp))
                StepPreviewCard(title = "网格校准", bitmap = s.stepPreviews[4])
            }
            is UiState.Error -> {
                Text(s.message, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun StepPreviewCard(title: String, bitmap: Bitmap?) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            ) {
                Text(
                    text = if (expanded) "▼ $title" else "▶ $title",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Medium
                )
                if (bitmap == null) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            AnimatedVisibility(visible = expanded && bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = title,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                )
            }
        }
    }
}
