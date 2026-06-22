# 项目重组设计方案

## 目标
在**不修改业务逻辑**的前提下重组项目结构，解决根目录杂乱、包名默认、工具类散落的问题。

## 变更清单

### 1. 包名重命名
- `com.example.xq` → `io.github.jiangood.xq`
- 所有 Java 文件的 `package` 声明和 `import` 同步更新

### 2. 新增 util 子包
- `FenUtil.java` → `io.github.jiangood.xq.util`
- `NameUtil.java` → `io.github.jiangood.xq.util`

### 3. 文件迁移

| 原路径 | 新路径 | 原因 |
|--------|--------|------|
| `opencv_java4110.dll` | `lib/opencv_java4110.dll` | 与 Linux .so 统一存放 |
| `img_1.png` | `docs/images/img_1.png` | 文档资源归位 |
| `img_2.png` | `docs/images/img_2.png` | 文档资源归位 |

### 4. DLL 加载方式调整
- `System.loadLibrary(Core.NATIVE_LIBRARY_NAME)` → `System.load("lib/opencv_java4110.dll")`
- 适应 DLL 迁移到 `lib/` 目录

### 5. README.md
- 更新图片引用路径

### 6. 不受影响的部分
- 所有业务逻辑、算法、阈值、接口签名
- 测试逻辑（仅更新 package/import）
- `template/`、`demos/` 目录结构不变
- `pom.xml` 中的依赖和插件配置不变

## 最终目录结构

```
xiangqi/
├── pom.xml
├── README.md
├── lib/
│   ├── opencv-4110.jar
│   ├── opencv_java4110.dll
│   ├── libopencv_java4110.so
│   └── Pikafish-20250110/
├── docs/
│   └── images/
│       ├── img_1.png
│       └── img_2.png
├── template/
├── demos/
└── src/
    ├── main/java/io/github/jiangood/xq/
    │   ├── XqApplication.java
    │   ├── MainService.java
    │   ├── util/
    │   │   ├── FenUtil.java
    │   │   └── NameUtil.java
    │   ├── opencv/
    │   │   └── OpenCvUtil.java
    │   └── engine/
    │       └── PikafishProcessHandler.java
    └── test/java/io/github/jiangood/xq/
        └── FenTest.java
```
