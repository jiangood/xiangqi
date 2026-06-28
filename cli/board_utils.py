import cv2
import numpy as np
from pathlib import Path
import logging
import os

log = logging.getLogger(__name__)


def crop_center(img, ratio=4/3):
    h, w = img.shape[:2]
    if h / w <= ratio:
        return img, 0
    crop_h = int(w * ratio)
    y = (h - crop_h) // 2
    return img[y:y + crop_h, :], y


def locate_board(img):
    blurred = cv2.GaussianBlur(img, (5, 5), 0)
    edges = cv2.Canny(blurred, 30, 100)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
    dilated = cv2.dilate(edges, kernel)
    contours, _ = cv2.findContours(dilated, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    log.info("locateBoard: %d contours", len(contours))

    largest = None
    largest_area = 0
    total_pixels = img.shape[0] * img.shape[1]
    for cnt in contours:
        x, y, w, h = cv2.boundingRect(cnt)
        area = w * h
        if area > total_pixels * 0.1 and area > largest_area:
            largest_area = area
            largest = (x, y, w, h)

    if largest is None:
        raise RuntimeError("未能定位棋盘区域")
    return largest


def calibrate_grid(matches, board_rect):
    bx, by, bw, bh = board_rect

    if len(matches) < 16:
        margin = int(min(bw, bh) * 0.05)
        gl = bx + margin
        gt = by + margin
        gw = bw - 2 * margin
        gh = bh - 2 * margin
        cw = gw / 8.0
        ch = gh / 9.0
        return [[(gl + c * cw, gt + r * ch) for c in range(9)] for r in range(10)]

    points = list(matches.keys())
    min_y = min(p[1] for p in points)
    max_y = max(p[1] for p in points)
    min_x = min(p[0] for p in points)
    max_x = max(p[0] for p in points)

    cell_h = (max_y - min_y) / 9.0
    cell_w = (max_x - min_x) / 8.0

    return [[(bx + min_x + c * cell_w, by + min_y + r * cell_h) for c in range(9)] for r in range(10)]


def assign_pieces_to_grid(match_result, calibrated_grid, board_rect):
    board = [[None for _ in range(9)] for _ in range(10)]
    bx, by, _, _ = board_rect

    cell_h = calibrated_grid[1][0][1] - calibrated_grid[0][0][1]
    cell_w = calibrated_grid[0][1][0] - calibrated_grid[0][0][0]
    cell_radius = max(cell_h, cell_w) / 3.0

    for pt, name in match_result.items():
        best_dist = float('inf')
        br, bc = -1, -1
        for r in range(10):
            for c in range(9):
                gx = calibrated_grid[r][c][0] - bx
                gy = calibrated_grid[r][c][1] - by
                dx = pt[0] - gx
                dy = pt[1] - gy
                d = dx * dx + dy * dy
                if d < best_dist:
                    best_dist = d
                    br, bc = r, c
        if best_dist <= cell_radius * cell_radius and br >= 0 and bc >= 0 and board[br][bc] is None:
            board[br][bc] = name

    return board


def save_visualization(image_path, board_rect, detections, grid, img=None):
    save_flag = os.environ.get('XQ_SAVE_RESULT', '').lower() in ('1', 'true', 'yes')
    if not save_flag:
        return

    if img is None:
        img = cv2.imread(image_path)
    if img is None:
        return
    bx, by, bw, bh = board_rect

    for r in range(10):
        for c in range(9):
            gx, gy = grid[r][c]
            cv2.drawMarker(img, (int(gx), int(gy)), (0, 200, 0), cv2.MARKER_CROSS, 10, 1)

    cv2.rectangle(img, (bx, by), (bx + bw, by + bh), (255, 0, 0), 2)

    cell_w = grid[0][1][0] - grid[0][0][0]
    cell_h = grid[1][0][1] - grid[0][0][1]
    for pt, name in detections.items():
        color = (0, 0, 255) if name.startswith('r') else (0, 0, 0)
        ax = bx + pt[0]
        ay = by + pt[1]
        x1 = int(ax - cell_w / 2)
        y1 = int(ay - cell_h / 2)
        x2 = int(ax + cell_w / 2)
        y2 = int(ay + cell_h / 2)
        cv2.rectangle(img, (x1, y1), (x2, y2), color, 2)
        cv2.putText(img, name, (x1, max(y1 - 4, 0)), cv2.FONT_HERSHEY_SIMPLEX, 0.6, color, 2)

    img_file = Path(image_path)
    tmp_dir = img_file.parent / "tmp"
    tmp_dir.mkdir(parents=True, exist_ok=True)
    out = tmp_dir / (img_file.stem + "-result.jpg")
    cv2.imwrite(str(out), img)
    log.info("识别结果图已保存: %s", out)
