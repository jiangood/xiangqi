# 中国象棋局面分析

手机截图场景。截图中棋盘网格一定在正中心。

## 使用流程

### 首次使用：棋盘棋子校准

1. 打开设置 → "棋盘棋子校准"
2. 选择一张**开局局面**截图（确保 14 类棋子齐全）
3. 自动检测棋盘网格，在图片上叠加显示
4. 可双指缩放/平移检查网格是否对齐
5. 确认方向正确后点击"确认校准"
6. 程序自动按标准开局阵型裁切 32 个棋子图像并保存为模板

### 日常分析

选图 → 模板匹配识别棋子 → 引擎分析走法

不再需要 YOLO / ONNX 模型。

## Android

支持安卓手机，基于 Pikafish 引擎进行棋局分析。

- Kotlin + Jetpack Compose + OpenCV + Pikafish 引擎
- 构建: `.\gradlew assembleDebug`

## FEN 格式

FEN (Forsyth–Edwards Notation) 是象棋局面的标准文本表示，也是引擎输入格式。

- 红色棋子大写：K=帅, A=仕, B=相, R=車, N=馬, C=炮, P=兵
- 黑色棋子小写：k=将, a=士, b=象, r=车, n=马, c=炮, p=卒
- 数字表示连续空格数，`/` 分隔行

参考：[中国象棋协议 - FEN](https://www.xqbase.com/protocol/cchess_move.htm)

## 引擎（Android 用）

https://www.pikafish.com

引擎速度：vnni512 > avx512 > avx512f > avxvnni > bmi2 > avx2 > sse41-popcnt > ssse3，根据 CPU 选择。

### UCI 命令参考

```
uci
position fen r2ak1b1r/4a4/2n1b1nc1/p1p1p1p1p/2c6/6P2/P3P3P/N1CC2N2/9/1RBAKAB1R w
go depth 20
```
