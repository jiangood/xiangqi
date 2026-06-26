# Fix Lazy Initialization on Floating Button Click

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Initialize the AnalysisEngine eagerly when FloatingBubbleService starts (or when app starts), so clicking the floating button "支" doesn't trigger lazy initialization logs.

**Architecture:** There are two separate initialization paths:
1. `AnalysisViewModel` (used by MainActivity UI) - initializes asynchronously at app startup
2. `AnalysisEngine` (used by FloatingBubbleService) - initializes LAZILY on first `analyze()` call

The fix is to make `AnalysisEngine` initialize eagerly when `FloatingBubbleService` starts, reusing the same initialization logic but running it proactively instead of on first use.

**Tech Stack:** Kotlin, Android, Coroutines, ONNX Runtime, Pikafish Engine

---

### Task 1: Add eager initialization to AnalysisEngine

**Files:**
- Modify: `android/app/src/main/java/io/github/jiangood/xq/analysis/AnalysisEngine.kt`

- [ ] **Step 1: Add `initEagerly(context: Context)` function to AnalysisEngine**

```kotlin
    suspend fun initEagerly(context: Context) {
        withContext(Dispatchers.IO) {
            if (initStarted) {
                AppLog.add("[引擎] 已经初始化，跳过急切初始化")
                return@withContext
            }
            initStarted = true
            AppLog.add("[引擎] 急切初始化开始...")
            init(context)
        }
    }
```

- [ ] **Step 2: Make `init(context: Context)` internal (not private) so it can be called from initEagerly**

```kotlin
    internal suspend fun init(context: Context) {
```

- [ ] **Step 3: Verify the change compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/io/github/jiangood/xq/analysis/AnalysisEngine.kt
git commit -m "feat(analysis): add eager initialization API to AnalysisEngine"
```

---

### Task 2: Call eager initialization from FloatingBubbleService.onCreate

**Files:**
- Modify: `android/app/src/main/java/io/github/jiangood/xq/service/FloatingBubbleService.kt`

- [ ] **Step 1: Add coroutine scope launch in onCreate to call initEagerly**

```kotlin
    override fun onCreate() {
        super.onCreate()
        AppLog.add("[悬浮窗] onCreate")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        AppLog.add("[悬浮窗] WindowManager 获取成功")
        if (!startForegroundSafe()) {
            AppLog.add("[悬浮窗] 前台服务启动失败，停止服务")
            stopSelf()
            return
        }
        AppLog.add("[悬浮窗] 前台服务启动成功")
        
        // NEW: Eagerly initialize AnalysisEngine so first click is fast
        scope.launch {
            AppLog.add("[悬浮窗] 急切初始化分析引擎...")
            AnalysisEngine.initEagerly(this@FloatingBubbleService)
            AppLog.add("[悬浮窗] 分析引擎急切初始化完成")
        }
        
        showBubble()
    }
```

- [ ] **Step 2: Verify the change compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/io/github/jiangood/xq/service/FloatingBubbleService.kt
git commit -m "feat(floating): eagerly initialize AnalysisEngine on service start"
```

---

### Task 3: (Optional) Also initialize in MainActivity for consistency

**Files:**
- Modify: `android/app/src/main/java/io/github/jiangood/xq/MainActivity.kt`

- [ ] **Step 1: Add AnalysisEngine.initEagerly call in MainActivity.onCreate after viewModel init**

```kotlin
        viewModel.initOpenCV(this)
        viewModel.initEngine(this)
        viewModel.initRecognizer(this)
        
        // NEW: Also initialize the shared AnalysisEngine for floating service
        viewModelScope.launch {
            AnalysisEngine.initEagerly(this@MainActivity)
        }
```

- [ ] **Step 2: Verify and commit**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
```bash
git add android/app/src/main/java/io/github/jiangood/xq/MainActivity.kt
git commit -m "feat(main): eagerly initialize AnalysisEngine at app startup"
```

---

### Task 4: Verify the fix works (Manual Testing)

**Test Plan:**
1. Build and install the app: `cd android && ./gradlew :app:installDebug`
2. Open app, grant overlay permission, grant notification permission, grant screen capture permission
3. Wait for floating button to appear
4. Click the floating button "支" immediately
5. Check logs (logcat or AppLog UI): Should NOT see "初始化 NNUE...", "启动引擎进程...", "加载 ONNX 模型..." logs on click
6. Those logs should appear earlier during service startup or app startup

**Expected Log Sequence (after fix):**
```
[悬浮窗] onCreate
[悬浮窗] 前台服务启动成功
[悬浮窗] 急切初始化分析引擎...
[引擎] 初始化 NNUE...
[引擎] 启动引擎进程...
[引擎] 加载 ONNX 模型...
[引擎] ONNX 模型加载成功
[悬浮窗] 分析引擎急切初始化完成
[悬浮窗] 显示悬浮按钮...
[悬浮窗] 按钮已添加到窗口
... (user clicks button)
[悬浮窗] 按钮被点击
[悬浮窗] mediaProjection 已就绪，直接截屏
[悬浮窗] 开始截屏...
[悬浮窗] 截屏成功: ...
[悬浮窗] 开始分析...
[引擎] 开始棋盘识别: ...
[引擎] 棋盘识别完成
[引擎] FEN: ...
[引擎] 引擎分析中...
[引擎] 引擎返回 X 条走法
[悬浮窗] 分析成功: ...
```

---

### Task 5: Run existing tests to ensure no regression

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: All tests pass

---

### Summary of Changes

| File | Change |
|------|--------|
| `AnalysisEngine.kt` | Add `initEagerly()` public API, make `init()` internal |
| `FloatingBubbleService.kt` | Call `AnalysisEngine.initEagerly()` in `onCreate()` via coroutine |
| `MainActivity.kt` (optional) | Also call `AnalysisEngine.initEagerly()` at app startup |

This ensures the heavy initialization (NNUE extraction, engine startup, ONNX model loading) happens **before** the user clicks the floating button, eliminating the delay and log spam on first click.