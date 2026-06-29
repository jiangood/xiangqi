package io.github.jiangood.xq.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jiangood.xq.BuildConfig
import io.github.jiangood.xq.settings.CalibrationManager
import io.github.jiangood.xq.settings.SettingsManager
import java.io.File

private val PIECE_TYPES = listOf(
    "rk", "ra", "rb", "rr", "rn", "rc", "rp",
    "bk", "ba", "bb", "br", "bn", "bc", "bp"
)

@Composable
private fun rememberTemplates(context: android.content.Context): State<List<Bitmap>> {
    return remember {
        mutableStateOf(
            try {
                val dir = CalibrationManager.getDir(context)
                PIECE_TYPES.mapNotNull { type ->
                    dir.listFiles { f -> f.name.startsWith("${type}_") }
                        ?.firstOrNull()
                        ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                }
            } catch (_: Exception) { emptyList() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCalibration: () -> Unit = {}
) {
    var depth by remember { mutableStateOf(SettingsManager.getDepth()) }
    var threads by remember { mutableStateOf(SettingsManager.getThreads()) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = {
                        SettingsManager.setDepth(depth)
                        SettingsManager.setThreads(threads)
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Text("搜索深度", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("当前: $depth", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = depth.toFloat(),
                onValueChange = { depth = it.toInt() },
                valueRange = 5f..30f,
                steps = 24,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("5", fontSize = 12.sp)
                Text("30", fontSize = 12.sp)
            }

            HorizontalDivider()

            Text("线程数", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("当前: $threads", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = threads.toFloat(),
                onValueChange = { threads = it.toInt() },
                valueRange = 1f..8f,
                steps = 6,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("1", fontSize = 12.sp)
                Text("8", fontSize = 12.sp)
            }

            Spacer(Modifier.weight(1f))

            HorizontalDivider()
            Text("棋盘棋子校准", fontWeight = FontWeight.Bold, fontSize = 16.sp)

            val calibrated = CalibrationManager.isCalibrated(context)
            if (calibrated) {
                val templates by rememberTemplates(context)
                Text(
                    "已校准 (${templates.size} 枚模板)",
                    fontSize = 14.sp,
                    color = Color(0xFF4CAF50)
                )
                if (templates.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(templates) { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .width(40.dp)
                                    .height(40.dp)
                                    .background(Color(0xFFE0E0E0))
                            )
                        }
                    }
                }
            } else {
                Text(
                    "未校准，请选择开局截图",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onOpenCalibration,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(if (calibrated) "重新校准" else "开始校准", fontSize = 16.sp)
            }
            Spacer(Modifier.height(16.dp))

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
}
