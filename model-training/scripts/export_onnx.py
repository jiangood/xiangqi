from ultralytics import YOLO

WEIGHTS = "runs/detect/models/xiangqi_yolo/weights/best.pt"

model = YOLO(WEIGHTS)
model.export(format="onnx", imgsz=640, simplify=True, dynamic=False)
print("ONNX export complete:", WEIGHTS.replace(".pt", ".onnx"))
