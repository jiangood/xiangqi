# Lite Version Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `full`/`lite` build flavors to the Android project, where `lite` downloads model files on first launch instead of bundling them (~50MB APK reduction).

**Architecture:** Add Android product flavors to `app/build.gradle.kts`. Move model files from `src/main/assets/` to `src/full/assets/`. Add `ModelDownloader` that downloads `pikafish.nnue` and `xiangqi_yolo.onnx` from GitHub raw on the `models` branch. Modify `AnalysisEngine.init()` to branch on `BuildConfig.MODELS_BUNDLED`. Add a `Downloading` UI state with progress display. Both flavors share the same `applicationId` and app identity.

**Tech Stack:** Android (Kotlin, Jetpack Compose), Gradle product flavors, `HttpURLConnection` (no extra dependencies)

---

### Task 1: Add productFlavors to build.gradle.kts

**Files:**
- Modify: `android/app/build.gradle.kts`

- [ ] **Step 1: Add flavorDimensions and productFlavors block**

After `kotlinOptions` block (line 73) and before `dependencies` (line 75), insert:

```kotlin
    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
            buildConfigField("boolean", "MODELS_BUNDLED", "true")
        }
        create("lite") {
            dimension = "edition"
            buildConfigField("boolean", "MODELS_BUNDLED", "false")
        }
    }
```

- [ ] **Step 2: Update APK output filename to include flavor name**

Change the `applicationVariants.all` block (lines 51-57) to:

```kotlin
    applicationVariants.all {
        val v = versionName
        outputs.all {
            this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            outputFileName = "xq-${v}-${flavor.name}.apk"
        }
    }
```

- [ ] **Step 3: Verify build.gradle.kts parses correctly**

Run: `cd android; .\gradlew :app:assembleFullDebug --no-daemon`
Expected: BUILD SUCCESSFUL (APK at `app/build/outputs/apk/full/debug/`)

- [ ] **Step 4: Commit**

```bash
git add android/app/build.gradle.kts
git commit -m "build: add full/lite product flavors"
```

---

### Task 2: Move model files to full flavor source set

**Files:**
- Move: `android/app/src/main/assets/pikafish.nnue` → `android/app/src/full/assets/pikafish.nnue`
- Move: `android/app/src/main/assets/xiangqi_yolo.onnx` → `android/app/src/full/assets/xiangqi_yolo.onnx`

- [ ] **Step 1: Create full flavor assets directory and move files**

```bash
New-Item -ItemType Directory -Path "android/app/src/full/assets" -Force
Move-Item -LiteralPath "android/app/src/main/assets/pikafish.nnue" -Destination "android/app/src/full/assets/pikafish.nnue"
Move-Item -LiteralPath "android/app/src/main/assets/xiangqi_yolo.onnx" -Destination "android/app/src/full/assets/xiangqi_yolo.onnx"
```

- [ ] **Step 2: Verify structure**

Run: `Get-ChildItem -Path "android/app/src/full/assets/"`
Expected: `pikafish.nnue` and `xiangqi_yolo.onnx` listed

Run: `Get-ChildItem -Path "android/app/src/main/assets/"`
Expected: empty (or contains other files like `.gitkeep`)

- [ ] **Step 3: Build full flavor debug to verify assets are picked up**

Run: `cd android; .\gradlew :app:assembleFullDebug --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Build lite flavor debug to verify no assets**

Run: `cd android; .\gradlew :app:assembleLiteDebug --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add android/app/src/full/
git add android/app/src/main/assets/
git commit -m "build: move model files to full flavor source set"
```

---

### Task 3: Create ModelDownloader.kt

**Files:**
- Create: `android/app/src/main/java/io/github/jiangood/xq/download/ModelDownloader.kt`

- [ ] **Step 1: Write ModelDownloader.kt**

```kotlin
package io.github.jiangood.xq.download

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ModelDownloader {

    private const val BASE_URL = "https://raw.githubusercontent.com/jiangood/xiangqi/models/"

    data class DownloadProgress(
        val fileName: String,
        val progress: Float // 0f..1f
    )

    suspend fun ensureModels(
        context: Context,
        onProgress: (DownloadProgress) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val files = listOf("pikafish.nnue", "xiangqi_yolo.onnx")
            for (fileName in files) {
                val targetFile = File(context.cacheDir, fileName)
                if (targetFile.exists() && targetFile.length() > 0) {
                    onProgress(DownloadProgress(fileName, 1f))
                    continue
                }
                downloadFile(fileName, targetFile) { progress ->
                    onProgress(DownloadProgress(fileName, progress))
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun downloadFile(
        fileName: String,
        targetFile: File,
        onProgress: (Float) -> Unit
    ) {
        val url = URL("$BASE_URL$fileName")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000

        try {
            connection.connect()
            val totalBytes = connection.contentLengthLong
            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(targetFile)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead: Long = 0

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (totalBytes > 0) {
                    onProgress(totalRead.toFloat() / totalBytes.toFloat())
                }
            }
            outputStream.flush()
            outputStream.close()
            inputStream.close()
        } finally {
            connection.disconnect()
        }

        if (!targetFile.exists() || targetFile.length() == 0L) {
            throw RuntimeException("Download failed: $fileName is empty or missing")
        }
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `cd android; .\gradlew :app:compileFullDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/io/github/jiangood/xq/download/ModelDownloader.kt
git commit -m "feat: add ModelDownloader for lite flavor"
```

---

### Task 4: Modify AnalysisEngine.kt to support lite flavor

**Files:**
- Modify: `android/app/src/main/java/io/github/jiangood/xq/analysis/AnalysisEngine.kt`

- [ ] **Step 1: Update init() to branch on MODELS_BUNDLED**

Replace the current `init()` method with:

```kotlin
    fun init(
        context: Context,
        onDownloadProgress: (fileName: String, progress: Float) -> Unit = { _, _ -> }
    ) {
        if (initComplete.isCompleted) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (BuildConfig.MODELS_BUNDLED) {
                    // full flavor: extract from assets
                    AppLog.add("[引擎] 解压模型文件...")
                    extractFromAssets(context, "pikafish.nnue", File(context.cacheDir, "pikafish.nnue"))
                    extractFromAssets(context, "xiangqi_yolo.onnx", File(context.cacheDir, "xiangqi_yolo.onnx"))
                } else {
                    // lite flavor: download if not exists
                    AppLog.add("[引擎] 检查模型文件...")
                    val result = ModelDownloader.ensureModels(context) { progress ->
                        onDownloadProgress(progress.fileName, progress.progress)
                    }
                    if (result.isFailure) {
                        AppLog.add("[引擎] 模型下载失败: ${result.exceptionOrNull()?.message}")
                        initComplete.completeExceptionally(result.exceptionOrNull() ?: RuntimeException("下载失败"))
                        return@launch
                    }
                }
                AppLog.add("[引擎] 启动引擎进程...")
                val engine = AndroidEngineClient(context)
                if (engine.start()) {
                    engineClient = engine
                    AppLog.add("[引擎] 引擎启动成功")
                } else {
                    AppLog.add("[引擎] 引擎启动失败")
                }
                AppLog.add("[引擎] 加载 ONNX 模型...")
                val modelFile = File(context.cacheDir, "xiangqi_yolo.onnx")
                boardRecognizer = YoloPieceRecognizer(modelFile.absolutePath)
                AppLog.add("[引擎] ONNX 模型加载成功")
                initComplete.complete(Unit)
            } catch (e: Exception) {
                AppLog.add("[引擎] 初始化失败: ${e.message}")
                initComplete.completeExceptionally(e)
            }
        }
    }
```

- [ ] **Step 2: Add extractFromAssets helper method**

Add before `suspend fun awaitInitialized()`:

```kotlin
    private fun extractFromAssets(context: Context, assetName: String, targetFile: File) {
        if (targetFile.exists()) {
            AppLog.add("[引擎] $assetName 已存在 (${targetFile.length()} bytes)")
            return
        }
        try {
            AppLog.add("[引擎] 解压 $assetName...")
            context.assets.open(assetName).use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }
            AppLog.add("[引擎] $assetName 解压完成 (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            AppLog.add("[引擎] $assetName 解压跳过: ${e.message}")
        }
    }
```

- [ ] **Step 3: Remove old inline extraction code**

Delete lines 36-70 (the old NNUE + ONNX inline extraction logic), since they are replaced by the new `extractFromAssets()` helper.

- [ ] **Step 4: Add necessary imports**

Ensure these imports exist at the top of the file:
```kotlin
import io.github.jiangood.xq.BuildConfig
import io.github.jiangood.xq.download.ModelDownloader
```

- [ ] **Step 5: Build to verify compilation**

Run: `cd android; .\gradlew :app:compileFullDebugKotlin :app:compileLiteDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/io/github/jiangood/xq/analysis/AnalysisEngine.kt
git commit -m "refactor: support lite flavor model download in AnalysisEngine"
```

---

### Task 5: Add Downloading state to ViewModel and UiState

**Files:**
- Modify: `android/app/src/main/java/io/github/jiangood/xq/viewmodel/AnalysisViewModel.kt`

- [ ] **Step 1: Add Downloading state to UiState sealed class**

Add to the existing `sealed class UiState`:

```kotlin
    data class Downloading(val progress: Float, val currentFile: String) : UiState()
```

- [ ] **Step 2: Add download tracking to AnalysisViewModel**

Add a new state field and method:

```kotlin
    private val _downloadProgress = MutableStateFlow<UiState>(UiState.Idle)
    val downloadState: StateFlow<UiState> = _downloadProgress

    fun startDownload(context: Context) {
        if (!BuildConfig.MODELS_BUNDLED) {
            viewModelScope.launch(Dispatchers.IO) {
                _downloadProgress.value = UiState.Downloading(0f, "pikafish.nnue")
                AnalysisEngine.init(context) { fileName, progress ->
                    val overallProgress = if (fileName == "pikafish.nnue") {
                        progress * 0.84f
                    } else {
                        0.84f + progress * 0.16f
                    }
                    _downloadProgress.value = UiState.Downloading(overallProgress, fileName)
                }
                // wait for init to complete
                try {
                    AnalysisEngine.awaitInitialized()
                    _downloadProgress.value = UiState.Idle
                } catch (e: Exception) {
                    _downloadProgress.value = UiState.Error("模型下载失败: ${e.message}")
                }
            }
        } else {
            // full: just init as before
            AnalysisEngine.init(context)
        }
    }
```

- [ ] **Step 3: Add import for BuildConfig**

```kotlin
import io.github.jiangood.xq.BuildConfig
```

- [ ] **Step 4: Build to verify compilation**

Run: `cd android; .\gradlew :app:compileFullDebugKotlin :app:compileLiteDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/io/github/jiangood/xq/viewmodel/AnalysisViewModel.kt
git commit -m "feat: add Downloading state and download flow to ViewModel"
```

---

### Task 6: Add download progress UI to MainScreen

**Files:**
- Modify: `android/app/src/main/java/io/github/jiangood/xq/ui/MainScreen.kt`
- Modify: `android/app/src/main/java/io/github/jiangood/xq/MainActivity.kt`

- [ ] **Step 1: Add download progress UI to MainScreen.kt**

Add a new composable for the download screen, and add a `Downloading` branch to the `when` block (after the `Idle` branch, line 77):

```kotlin
            is UiState.Downloading -> {
                DownloadingContent(s.currentFile, s.progress)
            }
```

Add the composable function at the bottom of the file (before the closing of the file):

```kotlin
@Composable
private fun DownloadingContent(currentFile: String, progress: Float) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "象棋支招",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(32.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "${(progress * 100).toInt()}%",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "正在下载模型文件...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = currentFile,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
```

- [ ] **Step 2: Update MainActivity.kt to use download-aware init**

Replace `AnalysisEngine.init(this)` in `onCreate()` (line 112):

```kotlin
        viewModel.startDownload(this)
```

Replace line 112 (`AnalysisEngine.init(this)`) with the ViewModel call.

Also, modify the `setContent` block to pass `downloadState` to `MainScreen`:

```kotlin
        setContent {
            val downloadState by viewModel.downloadState.collectAsState()
            MainScreen(
                viewModel = viewModel,
                downloadState = downloadState,
                onPickImage = { ... },
                onToggleFloating = { ... }
            )
        }
```

Update the `MainScreen` composable signature in `MainScreen.kt`:

```kotlin
fun MainScreen(
    viewModel: AnalysisViewModel,
    downloadState: UiState,
    onPickImage: () -> Unit,
    onToggleFloating: (Boolean) -> Unit
) {
```

And in `MainScreen`, use `downloadState` for the download UI while keeping `state` for the analysis flow. The top of the composable should switch based on downloadState:

```kotlin
    // At the top of MainScreen, after floatingEnabled:
    when (val ds = downloadState) {
        is UiState.Downloading -> {
            DownloadingContent(ds.currentFile, ds.progress)
            return
        }
        is UiState.Error -> {
            Column(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("下载失败", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text(ds.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.startDownload(/* need context */) }) {
                    Text("重试")
                }
            }
            return
        }
        else -> { /* proceed to normal UI */ }
    }
```

Wait - `startDownload` needs a `Context`. For the retry button, we need the context. Let me pass it differently. The simpler approach is to use a `LaunchedEffect` in MainActivity to handle the download lifecycle, and keep the state observable in the ViewModel.

Actually, let me simplify this. In the ViewModel:

```kotlin
fun retryDownload(context: Context) {
    startDownload(context)
}
```

And in MainScreen, we need a context. Use `LocalContext.current`:

```kotlin
val context = LocalContext.current
Button(onClick = { viewModel.retryDownload(context) }) {
    Text("重试")
}
```

- [ ] **Step 3: Add imports**

MainScreen.kt needs:
```kotlin
import androidx.compose.material3.LinearProgressIndicator
```

- [ ] **Step 4: Build to verify compilation**

Run: `cd android; .\gradlew :app:compileFullDebugKotlin :app:compileLiteDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/io/github/jiangood/xq/ui/MainScreen.kt
git add android/app/src/main/java/io/github/jiangood/xq/MainActivity.kt
git commit -m "feat: add download progress UI for lite flavor"
```

---

### Task 7: Add INTERNET permission to AndroidManifest

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add INTERNET permission**

After line 10 (POST_NOTIFICATIONS), add:

```xml
    <uses-permission android:name="android.permission.INTERNET" />
```

- [ ] **Step 2: Build to verify**

Run: `cd android; .\gradlew :app:compileFullDebugKotlin :app:compileLiteDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/AndroidManifest.xml
git commit -m "manifest: add INTERNET permission for lite flavor model download"
```

---

### Task 8: Update CI to build both flavors

**Files:**
- Modify: `.github/workflows/build-apk.yml`

- [ ] **Step 1: Update the build command**

Change the build step (line 71) from:
```yaml
          ./gradlew assembleRelease --no-daemon
```
to:
```yaml
          ./gradlew assembleFullRelease assembleLiteRelease --no-daemon
```

- [ ] **Step 2: Update the file upload pattern**

Change the `files` line (line 75) from:
```yaml
          files: android/app/build/outputs/apk/release/xq-*.apk
```
to:
```yaml
          files: |
            android/app/build/outputs/apk/fullRelease/xq-*.apk
            android/app/build/outputs/apk/liteRelease/xq-*.apk
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/build-apk.yml
git commit -m "ci: build both full and lite APK flavors on release"
```

---

### Plan Self-Review

1. **Spec coverage:**
   - ✅ Build configuration (Task 1)
   - ✅ Source directory layout with full/lite flavors (Task 2)
   - ✅ ModelDownloader (Task 3)
   - ✅ AnalysisEngine branching (Task 4)
   - ✅ UiState.Downloading + ViewModel (Task 5)
   - ✅ Download progress UI (Task 6)
   - ✅ INTERNET permission (Task 7)
   - ✅ CI changes (Task 8)
   - ✅ Edge cases: retry button, progress display, file existence check - covered

2. **Placeholder scan:** No TBD/TODO/fill-in-later patterns found. Every step has complete code.

3. **Type consistency:** `UiState.Downloading(progress: Float, currentFile: String)` used consistently across ViewModel, MainScreen, and ModelDownloader progress callbacks.
