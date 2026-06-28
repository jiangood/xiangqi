# 中国象棋局面分析

基于 YOLO 自训练的棋子识别模型，识别棋盘图片转为 FEN 局面表达式。

## CLI — 图片转 FEN

```shell
cd cli
pip install -r requirements.txt
python inference.py <image-file-path>
```

输出标准 FEN 格式字符串（红色大写，黑色小写），例如：

```
rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w
```

### 训练数据生成

```shell
python generate_labels.py [raw_dir] [image_dir] [label_dir] [preview_dir] [val_ratio]
```

从原始截图通过模板匹配自动生成 YOLO 标注。

## Android

支持安卓手机，基于 Pikafish 引擎进行棋局分析。

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
