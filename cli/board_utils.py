import cv2
import numpy as np
from pathlib import Path
import logging
import os

log = logging.getLogger(__name__)


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
    return largest  # (x,y,w,h) 棋盘外接矩形，含边框/装饰，非精确网格边界


def _extract_groups(cnt, threshold, gap):
    lr = np.where(cnt > threshold)[0]
    if len(lr) < 5:
        return None
    groups = []
    st = lr[0]
    for i in range(1, len(lr)):
        if lr[i] - lr[i - 1] > gap:
            groups.append(int((st + lr[i - 1]) / 2.0))
            st = lr[i]
    groups.append(int((st + lr[-1]) / 2.0))
    return np.array(groups)


def _find_best_line_chain(groups, cell_size, max_err=0.2, min_chain=4):
    if groups is None or len(groups) < 2:
        return None
    best_chain = []
    n = len(groups)
    for i in range(n - 1):
        d = groups[i+1] - groups[i]
        if abs(d / cell_size - 1) >= max_err:
            continue
        chain = [groups[i], groups[i+1]]
        expected = groups[i+1] + d
        for k in range(i+2, n):
            if abs(groups[k] - expected) <= cell_size * max_err:
                chain.append(groups[k])
                expected = groups[k] + d
            elif groups[k] > expected + cell_size * max_err:
                break
        expected = groups[i] - d
        for k in range(i-1, -1, -1):
            if abs(groups[k] - expected) <= cell_size * max_err:
                chain.insert(0, groups[k])
                expected = groups[k] - d
            elif groups[k] < expected - cell_size * max_err:
                break
        if len(chain) > len(best_chain):
            best_chain = chain
    return np.array(best_chain) if len(best_chain) >= min_chain else None


def detect_grid_lines(binary_img, cell_size):
    h, w = binary_img.shape[:2]
    fg = cv2.bitwise_not(binary_img) if np.count_nonzero(binary_img) > w * h * 0.5 else binary_img

    k_len = max(int(cell_size * 0.8), 1)
    gap = int(cell_size * 0.1)
    th = w * 0.15

    hk = cv2.getStructuringElement(cv2.MORPH_RECT, (k_len, 1))
    h_lines = cv2.erode(fg, hk)
    h_lines = cv2.dilate(h_lines, hk)
    h_cnt = np.count_nonzero(h_lines, axis=1)
    h_groups = _extract_groups(h_cnt, th, gap)
    h_chain = _find_best_line_chain(h_groups, cell_size) if h_groups is not None else None

    vk = cv2.getStructuringElement(cv2.MORPH_RECT, (1, k_len))
    v_lines = cv2.erode(fg, vk)
    v_lines = cv2.dilate(v_lines, vk)
    v_cnt = np.count_nonzero(v_lines, axis=0)
    v_groups = _extract_groups(v_cnt, h * 0.15, gap)
    v_chain = _find_best_line_chain(v_groups, cell_size) if v_groups is not None else None

    return h_chain, v_chain


def _estimate_start_row(h_chain, cs):
    N = len(h_chain)
    if N == 10:
        return 0
    spacings = h_chain[1:] - h_chain[:-1]
    first_sp, last_sp = spacings[0], spacings[-1]
    thresh = max(cs * 0.88, cs - 15)
    last_c = last_sp < thresh
    first_c = first_sp < thresh

    if N == 9:
        if last_c and not first_c:
            return 1
        if first_c and not last_c:
            return 0
        return None
    if N == 8:
        if last_c and not first_c:
            return 2
        if first_c and not last_c:
            return 0
        return 1
    return max(0, min(10 - N, round((10 - N) / 2)))


def _detect_river(h_chain, cell_size, crop_center_y):
    if h_chain is None or len(h_chain) < 6:
        return None
    spacings = h_chain[1:] - h_chain[:-1]
    cs = np.median(spacings)
    N = len(h_chain)

    R = _estimate_start_row(h_chain, cs)
    if R is not None:
        idx = 4 - R
        if 0 <= idx < N - 1:
            y4, y5 = h_chain[idx], h_chain[idx + 1]
            return (y4, y5, y5 - y4)

    best_pair = None
    best_score = float('inf')
    for i in range(N - 1):
        y1, y2 = h_chain[i], h_chain[i+1]
        spacing = y2 - y1
        midpoint = (y1 + y2) / 2.0
        dist_from_center = abs(midpoint - crop_center_y)
        spacing_deviation = abs(spacing / cs - 1)
        score = dist_from_center / max(crop_center_y, 1) + spacing_deviation
        if score < best_score:
            best_score = score
            best_pair = (y1, y2, spacing)
    return best_pair


def calibrate_grid(matches, board_rect, binary_img=None, img_size=None):
    bx, by, bw, bh = board_rect
    cell_size = bw / 9.0

    if binary_img is not None:
        h_chain, v_chain = detect_grid_lines(binary_img, cell_size)

        if h_chain is not None and len(h_chain) >= 6:
            h_center = (h_chain[0] + h_chain[-1]) / 2.0
            river = _detect_river(h_chain, cell_size, h_center)

            if river is not None:
                y4, y5, cs_h = river
                origin_y = y4 - 4 * cs_h
            else:
                spacings = h_chain[1:] - h_chain[:-1]
                cs_h = np.median(spacings)
                origin_y = h_center - 4.5 * cs_h

            if v_chain is not None and len(v_chain) >= 4:
                v_spacings = v_chain[1:] - v_chain[:-1]
                cs_w = np.median(v_spacings)
                v_first = max(0, int(v_chain[0] / cs_w + 0.3))
                origins = [v_chain[i] - (v_first + i) * cs_w for i in range(len(v_chain))]
                origin_x = np.median(origins)
            else:
                cs_w = cs_h
                origin_x = bw / 2.0 - 4 * cs_h

            rows = [origin_y + r * cs_h for r in range(10)]
            cols = [origin_x + c * cs_w for c in range(9)]
            return [[(cols[c] + bx, rows[r] + by) for c in range(9)] for r in range(10)]

    # 回退：假设网格从外边框向内偏移 cell_size/2（近似值）
    origin_x = bx + cell_size / 2.0
    origin_y = by + cell_size / 2.0
    rows = [origin_y + r * cell_size for r in range(10)]
    cols = [origin_x + c * cell_size for c in range(9)]
    return [[(cols[c], rows[r]) for c in range(9)] for r in range(10)]


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
