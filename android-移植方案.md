# Android 离线版移植方案

## 概述

将 `xiangqi`（Spring Boot + OpenCV + Pikafish 引擎）移植为完全离线的 Android App。参考 `xiangqi-android` 现有架构进行改造。

## 架构对比

| 模块 | 当前 Server 版 | 目标 Android 离线版 |
|---|---|---|
| OpenCV 图像识别 | 桌面 OpenCV，硬编码坐标 | Android OpenCV SDK，用户框选棋盘 |
| 引擎 | x86-64 Linux ELF | ARM64 (NDK 交叉编译) |
| 通信 | Spring Boot HTTP | `ProcessBuilder` 子进程 |
| UI | Freemarker 模板 | XML 原生布局 |
| 拍照输入 | HTTP 文件上传 | CameraX 相机 |
| 网络依赖 | 必须 | 完全离线 |

## 项目结构

沿用 `xiangqi-android` 现有结构，新增/修改文件：

```
app/src/main/java/cn/moon/xq/
├── MainActivity.java              # 保留，改为相机入口
├── ShareReceiverActivity.java     # 保留图库分享入口
├── CameraActivity.java            # 新增：CameraX 拍照
├── CropActivity.java              # 新增：裁剪棋盘区域
├── ResultActivity.java            # 新增：展示分析结果
├── OpenCvUtil.java                # 移植自 server，适配 Android
├── FenUtil.java                   # 移植自 server，纯逻辑
├── NameUtil.java                  # 移植自 server，纯逻辑
├── EngineService.java             # 新增：Pikafish ARM64 UCI 通信
└── MainService.java               # 新增：编排处理管线

app/src/main/jniLibs/arm64-v8a/
├── pikafish-armv8                 # 交叉编译产物
└── pikafish.nnue                  # NNUE 权重

app/src/main/assets/templates/     # 棋子模板图片（从 server template/ 复制）
├── ra.jpg, rb.jpg, rc.jpg, rk.jpg, rn.jpg, rp.jpg, rr.jpg
└── ba.jpg, bb.jpg, bc.jpg, bk.jpg, bn.jpg, bp.jpg, br.jpg
```

## 分步实施

### Step 1: Gradle 依赖配置

升级 `app/build.gradle.kts`，添加：

```kotlin
dependencies {
    // OpenCV Android SDK
    implementation 'com.quickbirdstudios:opencv:4.5.3.0'

    // CameraX
    implementation 'androidx.camera:camera-camera2:1.4.1'
    implementation 'androidx.camera:camera-lifecycle:1.4.1'
    implementation 'androidx.camera:camera-view:1.4.1'

    // 保留现有依赖
    implementation(libs.okhttp)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
}
```

权限声明（`AndroidManifest.xml`）：
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="true" />
```

### Step 2: 移植核心逻辑

**2.1 FenUtil.java** — 直接复制自 server，零改动
- 输入：`String[][] board` (10×9)
- 输出：FEN 字符串

**2.2 NameUtil.java** — 直接复制自 server，零改动
- 输入：UCI 走法（如 `e2e4`）
- 输出：中文棋谱（如 `炮二平五`）

**2.3 OpenCvUtil.java** — 核心改造
- 移除桌面 OpenCV 特有的硬编码坐标（`baseX=8, baseY=640`）
- 改为接收用户裁剪后的 Bitmap + 棋盘四角坐标
- 根据四角坐标计算透视变换，校正为 10×9 的正面棋盘
- 每格中心做模板匹配（`TM_CCOEFF_NORMED`，阈值 0.7）
- 棋子模板从 `assets/templates/` 加载

```java
public class OpenCvUtil {
    // 加载模板（应用启动时）
    public static Map<String, Mat> loadTemplates(Context context);

    // 解析棋盘
    public static String[][] parseBoard(Bitmap boardBitmap,
                                        Map<String, Mat> templates);
    // 新版：自动分割 10×9 网格，每格匹配
}
```

### Step 3: 交叉编译 Pikafish (ARM64)

在 Linux 环境（或 WSL）操作：

```bash
# 克隆 Pikafish 源码
git clone https://github.com/official-pikafish/Pikafish.git

# 使用 Android NDK 编译
export PATH=/path/to/android-ndk/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH
cd Pikafish/src
make -j build ARCH=armv8 COMP=ndk

# 产物
ls -lh pikafish-armv8   # ~15MB
```

将 `pikafish-armv8` 和 `pikafish.nnue`（从 `lib/Pikafish-20250110/` 获取）放入 `jniLibs/arm64-v8a/`。

### Step 4: CameraX 相机（CameraActivity.java）

```
┌─────────────────────────────┐
│                             │
│     ┌───────────────┐       │
│     │  棋盘对齐框     │       │
│     │  (半透明网格)   │       │
│     │               │       │
│     └───────────────┘       │
│                             │
│          [拍照按钮]          │
└─────────────────────────────┘
```

关键代码：
- `PreviewView` 实时取景
- `ImageCapture.takePicture()` 拍照
- 叠加层显示 9×10 网格线，引导用户对齐棋盘
- 拍照后跳转到 CropActivity

### Step 5: 裁剪棋盘（CropActivity.java）

```
┌─────────────────────────────┐
│                             │
│   ┌─── 拖动四角锚点 ───┐    │
│   │  ╔══════════════╗  │    │
│   │  ║  照片区域     ║  │    │
│   │  ║  (完整棋盘)   ║  │    │
│   │  ╚══════════════╝  │    │
│   └──────────────────────┘    │
│                             │
│      [确认]  [重拍]          │
└─────────────────────────────┘
```

- 显示拍好的照片
- 四个可拖动锚点定义棋盘四角
- 点击确认 → 裁剪并传给 MainService

### Step 6: 引擎服务（EngineService.java）

移植自 server 的 `PikafishProcessHandler.java`，适配 Android：

```java
public class EngineService {
    private Process engineProcess;
    private BufferedReader reader;
    private BufferedWriter writer;

    // 从 assets 解压二进制到 filesDir，设置可执行
    public static void extractEngine(Context context);

    // 启动引擎进程
    public void startEngine(File executable);

    // UCI 查询走法
    public String getBestMove(String fen, int depth);

    // 关闭引擎
    public void stopEngine();
}
```

Android 上的变更：
- `Runtime.exec()` → 使用 `context.getFilesDir()` 下的路径
- 需要先 `file.setExecutable(true)`
- 引擎二进制预编译为 ARM64 并打包在 APK 中

### Step 7: 主处理管线（MainService.java）

```
拍照 → [CropActivity] 裁剪棋盘
     → OpenCvUtil.parseBoard() → 10×9 数组
     → FenUtil.toFEN() → FEN 字符串
     → EngineService.getBestMove() → UCI 走法
     → NameUtil.toChineseNotation() → 中文棋谱
     → [ResultActivity] 展示结果
```

全部在异步协程中执行，UI 保持响应。

### Step 8: 结果展示（ResultActivity.java）

```
┌─────────────────────────────┐
│     分析结果                  │
│                             │
│   ┌─────────────────────┐   │
│   │  棋谱显示区域        │   │
│   │  炮二平五            │   │
│   └─────────────────────┘   │
│                             │
│   ┌─────────────────────┐   │
│   │  FEN: ...           │   │
│   │  深度: 12           │   │
│   │  耗时: 3.2s         │   │
│   └─────────────────────┘   │
│                             │
│      [再来一局] [分享]       │
└─────────────────────────────┘
```

## 分阶段里程碑

| 阶段 | 内容 | 预估工时 |
|---|---|---|
| P0: 可运行原型 | 移植核心逻辑 + 测试图库图片输入 | 1-2天 |
| P1: 相机集成 | CameraX 拍照 + 裁剪界面 | 1天 |
| P2: 引擎编译 | 交叉编译 Pikafish ARM64 + 集成 | 1天 |
| P3: 完整流程 | 端到端调通：拍照→识别→分析→展示 | 1天 |
| P4: 体验优化 | 出错处理、加载动画、对齐辅助线 | 0.5天 |

## 技术风险

| 风险 | 影响 | 应对 |
|---|---|---|
| OpenCV Android 版 API 差异 | 高 | 提前验证 `matchTemplate` 可用性 |
| NNUE 权重 ~15MB，首次解压慢 | 中 | 首次启动带进度提示 |
| 引擎深度 12 耗时 3-5s | 中 | 异步协程 + 加载动画 |
| 棋盘模板匹配精度 | 中 | 用户手动裁剪补偿 |
| Android NDK 编译坑 | 低 | 参考 Pikafish 官方 Makefile |
