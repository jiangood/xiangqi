# Xiangqi (中国象棋局面分析)

手机截图场景。截图中棋盘网格一定在正中心。

## CLI — 图片转 FEN

```bash
cd cli && pip install -r requirements.txt
python inference.py <image-path>
```

输出标准 FEN（红色大写，黑色小写），例如 `rnbakabnr/9/... w`。

## 推理管线

`locate_board()` (Canny+contour, 中心约束) → `calibrate_grid()` (线检测 + 几何对齐) → 按网格外沿 + 半棋子半径精裁 → YOLO ONNX → 颜色修正 → `assign_pieces_to_grid()` → `to_fen()`

- **精裁**: 网格校准在 YOLO 之前；裁剪到最外 grid line + `cell_size × 0.5` 边距，去除装饰边框
- **颜色修正**: `_correct_colors()` — 取检测点周围 9×9 窗口的红色分量修正被 YOLO 误分的棋子
- **FEN 走子方**: 根据 `k`(将) 在底部三行 (row 7-9) 判定 — 红方帅在底部则 `w`，黑方将在底部则 `b`

## 网格校准

- **主方案**: Otsu 二值化 → 形态学水平线检测 → 线链等距搜索 → 从线链中心外推 `origin_y`, `cell_size`（无需河界专用检测）
- **回退**: 几何先验 — `cell_size = bw/9`, 网格从外边框向内偏移 `cell_size/2`

## 棋盘定位 (`locate_board()`)

截图棋盘一定在正中心，所以定位简化（CLI 和 Android 端一致）：
- GaussianBlur(5×5) + Canny(30,100) + dilate(5×5)
- 仅保留包含图像中心、覆盖 < 85%、接近 9:10 宽高比的轮廓
- 未找到则回退为中心 85% 裁剪

## YOLO 模型

- `cli/models/xiangqi_yolo.onnx` (14 类), input 640×640 letterbox
- 14 类: `rk ra rb rr rn rc rp / bk ba bb br bn bc bp`
- 推理参数: conf=0.25, NMS=0.65
- 训练: `model-training/scripts/train.py` (YOLOv11n, `flipud=0.5`)
- 数据生成: `python generate_labels.py` — 模板匹配原截图 → YOLO label

## 测试

```bash
cd cli
python test_grid.py   # 生成网格叠加图到 demos/output/
python test_river.py  # 测试网格线检测
python -m pytest test_to_fen.py -v  # FEN 生成单元测试
```

## 训练

```bash
cd cli
python generate_labels.py [raw_dir] [image_dir] [label_dir] [preview_dir] [val_ratio]
cd ../model-training
pip install -r requirements.txt
python scripts/train.py
python scripts/export_onnx.py  # PT → ONNX, 复制到 cli/models/
```

## Android

- `android/` — Kotlin + Jetpack Compose + OpenCV + Pikafish 引擎
- 构建: `cd android && .\gradlew assembleDebug`
- 发布: CI 在 `v*` tag 触发, 构建 full APK (含 NNUE) 和 thin APK (不含), 创建 GitHub Release
- Thin build: `.\gradlew assembleRelease -Pthin`
- 发布 APK 需要 `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` secrets
- 文件命名: `xq-{versionName}-full.apk` / `xq-{versionName}-thin.apk`

## 关键约定

- 验证: 运行 `cd cli && python test_grid.py | findstr cell`
- 模型文件: `cli/models/xiangqi_yolo.onnx` (通过 Git LFS 追踪，不在 .gitignore 中)
- ONNX 导出: `model-training/scripts/export_onnx.py` (输入 PT 权重路径需要编辑)
