package io.github.jiangood.xq.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import io.github.jiangood.xq.settings.SettingsManager

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
            Button(
                onClick = onOpenCalibration,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("棋盘棋子校准", fontSize = 16.sp)
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
