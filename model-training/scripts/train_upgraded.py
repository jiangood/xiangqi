from ultralytics import YOLO

def train():
    # 升级模型：yolo11n -> yolo11s (small) 或 yolo11m (medium)
    # yolo11s: ~9.4M params, 更强分类能力；yolo11m: ~20M params, 更强但更慢
    model = YOLO("yolo11s.pt")  # 改为 s/m/l/x 之一

    results = model.train(
        data="data/dataset.yaml",
        epochs=100,
        imgsz=640,
        batch=8,          # CPU 内存限制，减小 batch
        device="cpu",
        workers=4,
        project="models",
        name="xiangqi_yolo_s",  # 新实验名
        exist_ok=True,
        pretrained=True,
        optimizer="auto",
        amp=False,        # CPU 关闭 AMP
        lr0=0.01,
        lrf=0.01,
        warmup_epochs=3,
        augment=True,
        patience=20,      # 早停
        save_period=10,   # 每 10 epoch 保存
    )

    print("Training complete. Best model saved to:", results.save_dir / "best.pt")

if __name__ == "__main__":
    train()