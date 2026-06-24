from ultralytics import YOLO

def train(resume_from=None):
    if resume_from:
        model = YOLO(resume_from)
        epochs = 110  # 100已完成 + 10
    else:
        model = YOLO("yolo11n.pt")
        epochs = 100

    results = model.train(
        data="data/dataset.yaml",
        epochs=epochs,
        imgsz=640,                   # 输入图片尺寸（像素）
        batch=16,                    # 每批样本数（显存/内存够可调大）
        device="cpu",                # 训练设备 cpu / cuda:0
        workers=8,                   # 数据加载并行数（5800H 16线程）
        cache=True,                  # 图像缓存到RAM，避免重复读盘
        save_period=10,              # 每10轮保存一次，减少磁盘写入
        project="models",            # 输出项目目录
        name="xiangqi_yolo",         # 本次实验名称
        exist_ok=True,               # 覆盖已有实验目录
        pretrained=True,             # 使用 COCO 预训练权重初始化
        optimizer="auto",            # 优化器自动选择（AdamW / SGD）
        amp=True,                    # 混合精度训练（加速、省显存）
        lr0=0.01,                    # 初始学习率
        lrf=0.01,                    # 最终学习率（= lr0 * lrf）
        warmup_epochs=3,             # 预热阶段（从 0 逐渐升到 lr0）
        augment=True,                # 启用内置数据增强
        flipud=0.5,                  # 上下翻转概率 50%，模拟对方视角：红方文字倒置 ↔ 黑方文字正立
    )

    print("Training complete. Best model saved to:", results.save_dir / "best.pt")

if __name__ == "__main__":
    import sys
    resume_from = sys.argv[1] if len(sys.argv) > 1 else None
    train(resume_from)
