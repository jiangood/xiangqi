import shutil
from pathlib import Path

from ultralytics import YOLO

WEIGHTS = "runs/detect/models/xiangqi_yolo/weights/best.pt"

model = YOLO(WEIGHTS)
model.export(format="onnx", imgsz=640, simplify=True, dynamic=False)
src = WEIGHTS.replace(".pt", ".onnx")
print("ONNX export complete:", src)

dst = Path(__file__).resolve().parent.parent.parent / "models" / "xiangqi_yolo.onnx"
shutil.copy(src, dst)
print("Copied to:", dst)
