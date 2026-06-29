# Xiangqi (中国象棋局面分析)

手机截图场景。截图中棋盘网格一定在正中心。

## 识别管线

校准(首次) → 模板匹配(复用网格) → FEN → 引擎

- **校准**: 选开局截图 → `BoardUtils.calibrateGrid()` → 网格叠加预览(可缩放/平移) → 确认 → 保存网格坐标 + 裁切 32 个棋子为模板
- **分析**: `TemplatePieceRecognizer.parseBoard()` — 对每个网格交叉点裁切棋子区域，`Imgproc.matchTemplate()` vs 14 类模板，取最佳匹配
- 红黑方向: 比较 `(row0,col4)` 和 `(row9,col4)` 两处棋子红色分量
- 校准数据存储: `filesDir/calibration/` (grid.json + meta.json + index.json + 32 个 png)

## Android

- Kotlin + Jetpack Compose + OpenCV + Pikafish 引擎
- 构建: `.\gradlew assembleDebug`
- 发布: CI 在 `v*` tag 触发, 构建 full APK (含 NNUE) 和 thin APK (不含), 创建 GitHub Release
- Thin build: `.\gradlew assembleRelease -Pthin`
- 发布 APK 需要 `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` secrets
- 文件命名: `xq-{versionName}-full.apk` / `xq-{versionName}-thin.apk`
