#!/usr/bin/env python3
import cv2
import numpy as np
import onnxruntime as ort
from pathlib import Path
import logging
import sys

from board_utils import locate_board, calibrate_grid, assign_pieces_to_grid, save_visualization

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s: %(message)s')
log = logging.getLogger('inference')

INPUT_SIZE = 640
CONF_THRESHOLD = 0.25
NMS_THRESHOLD = 0.65
NUM_CLASSES = 14
CLASS_NAMES = [
    "rk", "ra", "rb", "rr", "rn", "rc", "rp",
    "bk", "ba", "bb", "br", "bn", "bc", "bp",
]

PIECE_CHINESE = {
    "rk": "帅", "ra": "仕", "rb": "相", "rr": "車", "rn": "馬", "rc": "炮", "rp": "兵",
    "bk": "将", "ba": "士", "bb": "象", "br": "车", "bn": "马", "bc": "炮", "bp": "卒",
}


class YoloRecognizer:

    def __init__(self, model_path="models/xiangqi_yolo.onnx"):
        log.info("初始化 YoloRecognizer, 模型: %s", model_path)
        self.session = ort.InferenceSession(model_path, providers=['CPUExecutionProvider'])
        self.input_name = self.session.get_inputs()[0].name
        log.info("模型加载成功")

    def _preprocess(self, board_crop):
        h, w = board_crop.shape[:2]
        scale = min(INPUT_SIZE / w, INPUT_SIZE / h)
        new_w = int(w * scale)
        new_h = int(h * scale)
        pad_w = (INPUT_SIZE - new_w) // 2
        pad_h = (INPUT_SIZE - new_h) // 2
        pad_w_rest = (INPUT_SIZE - new_w) % 2
        pad_h_rest = (INPUT_SIZE - new_h) % 2

        resized = cv2.resize(board_crop, (new_w, new_h))
        padded = cv2.copyMakeBorder(
            resized, pad_h, pad_h + pad_h_rest, pad_w, pad_w + pad_w_rest,
            cv2.BORDER_CONSTANT, value=(114, 114, 114),
        )

        blob = cv2.cvtColor(padded, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        blob = np.transpose(blob, (2, 0, 1))
        blob = np.expand_dims(blob, axis=0)
        return blob, scale, pad_w, pad_h

    def _postprocess(self, output, orig_w, orig_h, scale, pad_w, pad_h):
        preds = output[0]
        num = preds.shape[1]

        dets = []
        for i in range(num):
            cx, cy, bw, bh = preds[0, i], preds[1, i], preds[2, i], preds[3, i]
            max_score = -1
            class_id = -1
            for j in range(NUM_CLASSES):
                s = preds[4 + j, i]
                if s > max_score:
                    max_score = s
                    class_id = j
            if max_score < CONF_THRESHOLD:
                continue
            x1 = cx - bw / 2
            y1 = cy - bh / 2
            x2 = cx + bw / 2
            y2 = cy + bh / 2
            dets.append((x1, y1, x2, y2, max_score, class_id))

        dets.sort(key=lambda d: d[4], reverse=True)
        kept = []
        for d in dets:
            overlap = any(self._iou(d, k) > NMS_THRESHOLD for k in kept)
            if not overlap:
                kept.append(d)

        log.info("NMS 后剩余 %d 个检测", len(kept))
        result = {}
        for d in kept:
            cx = (d[0] + d[2]) / 2
            cy = (d[1] + d[3]) / 2
            ox = max(0, min(orig_w, (cx - pad_w) / scale))
            oy = max(0, min(orig_h, (cy - pad_h) / scale))
            result[(int(ox), int(oy))] = CLASS_NAMES[d[5]]
        return result

    @staticmethod
    def _iou(a, b):
        ix1 = max(a[0], b[0])
        iy1 = max(a[1], b[1])
        ix2 = min(a[2], b[2])
        iy2 = min(a[3], b[3])
        inter = max(0, ix2 - ix1) * max(0, iy2 - iy1)
        aa = (a[2] - a[0]) * (a[3] - a[1])
        ab = (b[2] - b[0]) * (b[3] - b[1])
        return inter / (aa + ab - inter + 1e-6)

    def _correct_colors(self, detections, src_color, board_rect):
        bx, by, _, _ = board_rect
        for pt in list(detections.keys()):
            cls = detections[pt]
            detected_red = cls.startswith('r')
            img_x = int(pt[0] + bx)
            img_y = int(pt[1] + by)

            actual_red = False
            for dy in range(-4, 5):
                for dx in range(-4, 5):
                    px, py = img_x + dx, img_y + dy
                    if 0 <= px < src_color.shape[1] and 0 <= py < src_color.shape[0]:
                        if int(src_color[py, px, 2]) - int(src_color[py, px, 1]) > 60:
                            actual_red = True
                            break
                if actual_red:
                    break
            if detected_red != actual_red:
                corrected = ('b' + cls[1:]) if cls[0] == 'r' else ('r' + cls[1:])
                log.info("颜色修正: %s -> %s at (%d,%d)", cls, corrected, img_x, img_y)
                detections[pt] = corrected

    def run_inference(self, board_crop):
        h, w = board_crop.shape[:2]
        blob, scale, pad_w, pad_h = self._preprocess(board_crop)
        outputs = self.session.run(None, {self.input_name: blob})
        return self._postprocess(outputs[0], w, h, scale, pad_w, pad_h)

    def parse_board(self, image_path):
        log.info("加载图像: %s", image_path)
        src_color = cv2.imread(image_path)
        src_gray = cv2.cvtColor(src_color, cv2.COLOR_BGR2GRAY)

        board_rect = locate_board(src_gray)
        log.info("棋盘外边框: %s", board_rect)

        bx, by, bw, bh = board_rect
        board_crop = src_color[by:by + bh, bx:bx + bw].copy()

        infer_gray = cv2.cvtColor(board_crop, cv2.COLOR_BGR2GRAY)
        _, binary_img = cv2.threshold(infer_gray, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)
        board_crop = cv2.cvtColor(binary_img, cv2.COLOR_GRAY2BGR)

        detections = self.run_inference(board_crop)
        log.info("YOLO 检测到 %d 个棋子", len(detections))

        self._correct_colors(detections, src_color, board_rect)

        calibrated_grid = calibrate_grid(detections, board_rect, binary_img, src_color.shape[:2][::-1])
        log.info("自校准网格完成")

        save_visualization(image_path, board_rect, detections, calibrated_grid, img=src_color)

        return assign_pieces_to_grid(detections, calibrated_grid, board_rect)


def to_fen(board):
    if board is None or len(board) != 10 or len(board[0]) != 9:
        raise ValueError("棋盘必须是10行9列的二维数组")

    rows = []
    for row in board:
        buf = []
        empty = 0
        for cell in row:
            if cell is None or cell.strip() == '':
                empty += 1
            else:
                if empty:
                    buf.append(str(empty))
                    empty = 0
                c = cell[1]
                if cell[0] == 'r':
                    c = c.upper()
                buf.append(c)
        if empty:
            buf.append(str(empty))
        rows.append(''.join(buf))

    active = 'w'
    for i in range(9, 6, -1):
        found = False
        for j in range(9):
            p = board[i][j]
            if p and len(p) == 2 and p[1] == 'k':
                active = 'w' if p[0] == 'r' else 'b'
                found = True
                break
        if found:
            break

    return '/'.join(rows) + ' ' + active


def validate_position(board):
    errors = []
    if board is None:
        return ["棋盘为空"]
    if len(board) != 10 or len(board[0]) != 9:
        return [f"棋盘尺寸不正确: {len(board)}×{len(board[0])}，应为10×9"]

    names = {
        "rk": "红帅", "ra": "红仕", "rb": "红相", "rr": "红车", "rn": "红马", "rc": "红炮", "rp": "红兵",
        "bk": "黑将", "ba": "黑士", "bb": "黑象", "br": "黑车", "bn": "黑马", "bc": "黑炮", "bp": "黑卒",
    }
    for r in range(10):
        for c in range(9):
            p = board[r][c]
            if p is None or len(p) != 2:
                continue
            color, ptype = p[0], p[1]
            name = names.get(p, f"未知棋子({p})")
            pos = f"({r},{c})"
            red = color == 'r'
            if ptype in ('k', 'a'):
                if c < 3 or c > 5:
                    errors.append(f"{name}在{pos}位置异常：不在九宫范围内")
                if red and (r < 7 or r > 9):
                    errors.append(f"{name}在{pos}位置异常：不在九宫范围内")
                if not red and (r < 0 or r > 2):
                    errors.append(f"{name}在{pos}位置异常：不在九宫范围内")
            elif ptype == 'b':
                if red and (r < 5 or r > 9):
                    errors.append(f"{name}在{pos}位置异常：不在己方半场")
                if not red and (r < 0 or r > 4):
                    errors.append(f"{name}在{pos}位置异常：不在己方半场")
    return errors





def print_board(board):
    for i in range(10):
        row = ' '.join(PIECE_CHINESE.get(p, '＋') if p else '＋' for p in board[i])
        print(row)
    print("\n红方：帅 仕 相 車 馬 炮 兵")
    print("黑方：将 士 象 车 马 炮 卒")


def main():
    if len(sys.argv) < 2:
        print("Usage: python inference.py <image-file>", file=sys.stderr)
        sys.exit(1)

    image_path = sys.argv[1]

    recognizer = YoloRecognizer("models/xiangqi_yolo.onnx")
    board = recognizer.parse_board(image_path)

    warnings = validate_position(board)
    if warnings:
        for w in warnings:
            log.warning("局面验证: %s", w)

    print(to_fen(board))


if __name__ == "__main__":
    main()
