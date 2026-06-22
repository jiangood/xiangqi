from ultralytics import YOLO

def train():
    model = YOLO("yolo11n.pt")

    results = model.train(
        data="data/dataset.yaml",
        epochs=100,
        imgsz=640,
        batch=16,
        device="auto",
        workers=4,
        project="models",
        name="xiangqi_yolo",
        exist_ok=True,
        pretrained=True,
        optimizer="auto",
        amp=True,
        lr0=0.01,
        lrf=0.01,
        warmup_epochs=3,
        augment=True,
    )

    print("Training complete. Best model saved to:", results.save_dir / "best.pt")

if __name__ == "__main__":
    train()
