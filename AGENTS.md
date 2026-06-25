# Xiangqi (中国象棋局面分析)

## Build & Run

- `mvn clean package -DskipTests` → `cli/target/app.jar`
- `java -jar cli/target/app.jar <image-path>` (Java 17), or `cd cli && java -jar target/app.jar`
- Maven via aliyun mirror (pom.xml), OpenCV native JAR at `cli/lib/opencv-4110.jar`
- OpenCV native DLL: `cli/lib/opencv_java4110.dll` (Win) / `cli/libopencv_java4110.so` (Linux)

## Architecture

- Entry: `App.java` → `BoardService` → `PieceRecognizer` interface
- Two recognizers: `YoloPieceRecognizer` (ONNX, `USE_YOLO=true`) / `TemplateMatchRecognizer`
- `BoardUtils.locateBoard()`: Canny edge + contour finding; `calibrateGrid()`: piece positions → 10×9 grid
- `EngineClient`: wraps Pikafish UCI engine at `cli/bin/Pikafish-20250110/`; auto-selects best variant (vnni512→...→ssse3)
- `FenUtil`: board→FEN (red uppercase, e.g. `K`=帅, `k`=将); `NotationConverter`: engine move→Chinese notation
- Board convention: **black-on-top, red-on-bottom** assumed; `isBlackTop()` check auto-swaps colors if violated

## YOLO Model

- ONNX model at `cli/models/xiangqi_yolo.onnx`, input 1280×1280 letterbox, 14 classes
- Classes: rk ra rb rr rn rc rp / bk ba bb br bn bc bp
- Inference params: conf=0.25, NMS=0.65, intra threads=1
- Training: `model-training/scripts/train.py` (YOLOv11n, 640×640, `flipud=0.5`)
- Export: `export_onnx.py` (PT→ONNX at 640→deployed at 1280)
- Data prep: `YoloUtil.main()` reads raw screenshots, locates board, template-matches pieces → YOLO labels
- Template dir: `cli/template/` (14 piece images)

## Tests

- `mvn test` (JUnit 5 via surefire); test class: `ChessboardRecognizerTest`
- Test images in `cli/demos/`; expected FENs hardcoded in test file
- Uses `cn.hutool.core.lang.Assert` (not JUnit assertions) in `assertRecognizer`

## Key Conventions

- Pre-commit: verify by running `mvn test -f cli\pom.xml` (CLI module only)
- Model output ONNX goes to `cli/models/`; training artifacts to `model-training/data/`
- YOLO input during inference is 1280; training is 640 — resize mismatch intentional (letterbox padding handles it)
- `cli/lib/opencv_java4110.dll` must exist at runtime; loaded via `System.load()` in static initializer
- `.gitignore` ignores: training outputs (`model-training/data/images/`, labels, preview, runs), YOLO weights, raw data

## Android

- Android project at `android/`; build with `.\gradlew assembleDebug` (from `android/`)
- CI: `.github/workflows/build-apk.yml` — triggers on `v*` tag, runs tests, builds release APK, creates GitHub Release
- Release APK requires `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` secrets
- ABIs: `arm64-v8a` only in release; debug keeps all for emulator testing
- APK naming: `xq-{versionName}.apk`
- Model/data files not committed for Android; must be bundled separately
