# Android 中国象棋支招 App 设计文档

## 概述

在 `android/` 子目录下创建独立的 Android Gradle 项目，复用现有 CLI 的核心
Java 逻辑（棋盘识别、FEN 生成、记谱转换），实现：
- 用户从相册选择棋盘截图
- 全离线分析，优先展示推荐走法
- 可选展开查看中间步骤预览图（棋子识别、网格校准）以人工核验

## 项目结构

```
xiangqi/
├── android/                          # Android 独立 Gradle 项目
│   ├── build.gradle.kts              # 根构建文件
│   ├── settings.gradle.kts
│   ├── gradle.properties
│   ├── app/                          # 主应用模块 (Kotlin + Compose)
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── java/io/github/jiangood/xq/
│   │       │   ├── MainActivity.kt
│   │       │   ├── ui/MainScreen.kt          # 主界面 Composable
│   │       │   ├── viewmodel/AnalysisViewModel.kt
│   │       │   └── platform/AndroidImageUtils.kt  # Bitmap ↔ Mat 转换
│   │       ├── assets/
│   │       │   ├── xiangqi_yolo.onnx
│   │       │   ├── pikafish-armv8            # 从 皮卡鱼 20260131.zip 提取
│   │       │   ├── pikafish-armv8-dotprod    # 备选
│   │       │   ├── pikafish.nnue             # 神经网络权重
│   │       │   └── template/                 # 14 张模板图片
│   │       └── res/
│   ├── core/                         # Java library 模块
│   │   ├── build.gradle.kts
│   │   └── src/main/java/io/github/jiangood/xq/
│   │       ├── opencv/
│   │       │   ├── PieceRecognizer.java
│   │       │   ├── YoloPieceRecognizer.java  # ONNX 输入改为 assets 路径
│   │       │   ├── TemplateMatchRecognizer.java
│   │       │   ├── BoardUtils.java           # 直接复用
│   │       │   └── config/AppConfig.java
│   │       └── util/
│   │           ├── FenUtil.java              # 直接复用
│   │           └── NotationConverter.java    # 直接复用
│   └── models/                       # 模型/权重文件副本
```

## 技术选型

| 层面 | 选择 |
|------|------|
| UI 框架 | Jetpack Compose (Material3) |
| 语言 | Kotlin (UI) + Java (core) |
| 图片处理 | OpenCV Android SDK 4.11.0 (AAR) |
| ONNX 推理 | onnxruntime-android 1.20.0 |
| 象棋引擎 | pikafish-armv8 (ELF binary, ProcessBuilder 启动) |
| 最低 SDK | 26 (Android 8.0) |
| 目标 SDK | 34 (Android 14) |
| 构建 | Kotlin DSL Gradle |

## UI 布局

```
┌────────────────────────────┐
│  中国象棋支招                │
│                            │
│  ┌────────────────────┐    │
│  │   原始图片预览       │    │
│  └────────────────────┘    │
│                            │
│  [选择图片]   [开始分析]    │
│                            │
│  ═══════════════════════   │
│  ★ 推荐走法                 │
│      ┌──────────────┐     │
│      │   炮五进四     │     │  ← 大号醒目
│      └──────────────┘     │
│   ◀ 第 1/3 条 ▶           │
│                            │
│  ─── 分析详情 ───           │
│  ▶ 棋子识别                 │  ← Collapsible，默认收起
│  ▶ 网格校准                 │  ← Collapsible，默认收起
└────────────────────────────┘
```

## 交互流程

1. 用户点击"选择图片" → ActivityResultContracts.PickVisualMedia → 显示图片
2. 点击"开始分析" → ViewModel 启动后台协程：
   - Dispatchers.IO 上全速运行分析流水线，不生成预览图
3. 分析完成 → 第一时间展示推荐走法（中文记谱）
4. 后台继续生成步骤预览图（识别标注、网格覆盖），逐步填充到 UI
5. 引擎返回多条候选走法时，支持 ◀ ▶ 翻页浏览

## 分析流水线

```
图片 URI → ContentResolver → Bitmap → Mat

A. BoardUtils.locateBoard()          → 棋盘矩形
B. YoloPieceRecognizer.parseBoard()  → 棋子检测     → 预览图 1（用户步骤 3）
C. BoardUtils.calibrateGrid()        → 10×9 网格    → 预览图 2（用户步骤 4）
D. FenUtil.toFen()                   → FEN 字符串
E. EngineClient.getBestMove()        → UCI 走法
F. NotationConverter → 中文记谱                    → 推荐走法（用户步骤 5）

→ 先展示推荐走法（步骤 F 结果）
→ 后台生成预览图 1（棋子识别标注）、预览图 2（网格覆盖）
```

## 状态管理 (ViewModel)

```kotlin
sealed class UiState {
    object Idle : UiState()
    object Analyzing : UiState()
    data class Result(
        val moves: List<String>,
        val currentMoveIndex: Int = 0,
        val stepPreviews: Map<Int, Bitmap> = emptyMap()
    ) : UiState()
    data class Error(val message: String) : UiState()
}
```

## 核心逻辑复用策略

| 类 | 操作 | 说明 |
|-----|--------|------|
| BoardUtils.java | 直接复制 | 纯 OpenCV + 数学运算，import 适配 Android SDK |
| FenUtil.java | 直接复制 | 无外部依赖 |
| NotationConverter.java | 直接复制 | 无外部依赖 |
| PieceRecognizer.java | 直接复制 | 接口定义 |
| YoloPieceRecognizer.java | 少量修改 | OpenCVLoader.initLocal()，模型路径改为 assets 读取 |
| TemplateMatchRecognizer.java | 少量修改 | 同上 |
| EngineClient.java | 重构 | 路径改为 assets 提取的 armv8，Android 上 `getExternalFilesDir(null)` 作为运行目录 |
| AppConfig.java | 直接复制 | 配置标志 |

## EngineClient Android 适配

```java
// Android 版：从 assets 复制 pikafish-armv8 到私有目录，chmod 后 ProcessBuilder 启动
// UCI 通信逻辑与桌面版完全一致
```

Pikafish ARM64 二进制从 `皮卡鱼 20260131.zip` 提取，放在 `app/src/main/assets/pikafish-armv8`。

## 构建依赖

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.0")
    implementation("androidx.compose.ui:ui:1.7.0")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
}

// core/build.gradle.kts
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    // OpenCV Android SDK AAR
    implementation(fileTree("libs") { include("*.aar") })
}
```

## 异常处理

- 图片读取失败 → "无法读取图片"
- 棋盘定位失败 → "未检测到棋盘"
- 棋子识别为空 → "未识别到棋子"
- 引擎无响应/超时（15s）→ "引擎分析超时"
- 预览图生成失败 → 跳过该步骤，不阻塞 UI

## 测试策略

- `core/` 模块的 JUnit 测试直接复用 CLI 现有测试用例
- Android 端冒烟测试：用 `demos/` 测试图片验证全流程
- 真机验证：同一张图片在 CLI 和 Android 上的推荐走法一致
