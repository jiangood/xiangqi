# Xiangqi (中国象棋局面分析)

## Build & Run

- `cd cli && pip install -r requirements.txt`
- `python inference.py <image-path>` (Python 3.9+)
- Inference runs ONNX model (`models/xiangqi_yolo.onnx`) via onnxruntime + OpenCV
- Training data gen: `python generate_labels.py [raw_dir] [image_dir] [label_dir] [preview_dir] [val_ratio]`

## Architecture

- `inference.py`: YOLO ONNX inference → board grid → FEN → Pikafish UCI engine → Chinese notation
- `generate_labels.py`: template-matches raw screenshots → YOLO-format labels for training
 - `board_utils.py`: shared — `locateBoard()` (Canny+contour), `calibrateGrid()` (river-based grid), grid assignment, visualization
- `EngineClient`: wraps Pikafish UCI engine at `cli/bin/Pikafish-20250110/`; auto-selects best variant (vnni512→...→ssse3)
- `to_fen()`: board→FEN (red uppercase, e.g. `K`=帅, `k`=将)
- `convert_to_chinese_notation()`: engine UCI move→Chinese notation
- Board convention: natural orientation; FEN active color determined by king (k) in bottom 3 rows

## YOLO Model

- ONNX model at `cli/models/xiangqi_yolo.onnx`, input 640×640 letterbox, 14 classes
- Classes: rk ra rb rr rn rc rp / bk ba bb br bn bc bp
- Inference params: conf=0.25, NMS=0.65
- Training: `model-training/scripts/train.py` (YOLOv11n, 640×640, `flipud=0.5`)
- Export: `export_onnx.py` (PT→ONNX at 640→deployed at 640)
- Data prep: `python generate_labels.py` reads raw screenshots, locates board, template-matches pieces → YOLO labels
- Template dir: `cli/template/` (14 piece images)

## Tests

- `python test_grid.py` generates grid overlay images in `demos/output/`
- Each image shows detected grid intersections + avg piece offset
- Test images in `cli/demos/`

## Grid Calibration

- **Primary**: Detect 楚河汉界 (river) via morphological horizontal line detection
  - Otsu binary → bitwise_not → erode+dilate with long horizontal kernel → find line groups
  - Search for adjacent line pair near `5*cell_size` from crop top, spacing ≈ `cell_size`
  - First river line = row 4, second = row 5 → derive `origin_y`, `cell_size`
- **Fallback**: Geometric prior — `cell_size = bw/9`, grid starts at `cell_size/2` from board edge
  - Used when binary image unavailable or river detection fails
- **Validated by**: `test_grid.py` computes `avg_off` (avg pixel distance from pieces to grid intersections)

## Key Conventions

- Pre-commit: verify by running `cd cli && python test_grid.py | grep avg_off`
- Model output ONNX goes to `cli/models/`; training artifacts to `model-training/data/`
- `.gitignore` ignores: `__pycache__/`, training outputs (`model-training/data/images/`, labels, preview, runs), YOLO weights, raw data, `cli/logs/`

## Android

- Android project at `android/`; build with `.\gradlew assembleDebug` (from `android/`)
- CI: `.github/workflows/build-apk.yml` — triggers on `v*` tag, runs tests, builds release APK, creates GitHub Release
- Release APK requires `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` secrets
- ABIs: `arm64-v8a` only in release; debug keeps all for emulator testing
- APK naming: `xq-{versionName}.apk`
- Model/data files not committed for Android; must be bundled separately
