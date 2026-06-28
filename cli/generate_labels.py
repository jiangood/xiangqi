#!/usr/bin/env python3
import cv2
import numpy as np
from pathlib import Path
import logging
import random
import sys
import shutil
from concurrent.futures import ThreadPoolExecutor, as_completed

from board_utils import locate_board, calibrate_grid

log = logging.getLogger('generate_labels')

PIECE_CLASS_IDS = {
    "rk": 0, "ra": 1, "rb": 2, "rr": 3, "rn": 4, "rc": 5, "rp": 6,
    "bk": 7, "ba": 8, "bb": 9, "br": 10, "bn": 11, "bc": 12, "bp": 13,
}


def load_templates(template_dir="template"):
    templates = {}
    for f in Path(template_dir).iterdir():
        if f.suffix.lower() in ('.jpg', '.jpeg', '.png', '.bmp'):
            img = cv2.imread(str(f), cv2.IMREAD_GRAYSCALE)
            if img is not None:
                templates[f.stem] = img
    if not templates:
        raise RuntimeError(f"未加载到模板文件 from {template_dir}")
    log.info("加载了 %d 个模板", len(templates))
    return templates


def match_template_single(src, template, threshold=0.65):
    result = cv2.matchTemplate(src, template, cv2.TM_CCOEFF_NORMED)
    h, w = template.shape
    matches = [(x + w // 2, y + h // 2) for y in range(result.shape[0]) for x in range(result.shape[1])
               if result[y, x] >= threshold]

    if len(matches) <= 1:
        return matches

    filtered = []
    removed = [False] * len(matches)
    ts = w
    for i in range(len(matches)):
        if removed[i]:
            continue
        filtered.append(matches[i])
        for j in range(i + 1, len(matches)):
            if removed[j]:
                continue
            dx = matches[i][0] - matches[j][0]
            dy = matches[i][1] - matches[j][1]
            if (dx * dx + dy * dy) ** 0.5 < ts * 0.7:
                removed[j] = True
    return filtered


def match_template_all(src_gray, templates):
    result = {}
    for name, template in templates.items():
        for pt in match_template_single(src_gray, template):
            result[pt] = name
    return result


def process_one(image_path, image_dir, label_dir, preview_dir, base_name, templates):
    src_color = cv2.imread(image_path)
    src_gray = cv2.cvtColor(src_color, cv2.COLOR_BGR2GRAY)

    board_rect = locate_board(src_gray)
    bx, by, bw, bh = board_rect

    board_gray = src_gray[by:by + bh, bx:bx + bw]
    match_result = match_template_all(board_gray, templates)
    log.info("检测到 %d 个棋子", len(match_result))

    _, board_bin = cv2.threshold(board_gray, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)
    board_bgr = cv2.cvtColor(board_bin, cv2.COLOR_GRAY2BGR)

    calibrated_grid = calibrate_grid(match_result, board_rect)
    cell_w = calibrated_grid[0][1][0] - calibrated_grid[0][0][0]
    cell_h = calibrated_grid[1][0][1] - calibrated_grid[0][0][1]
    if len(match_result) < 16:
        cell_w = bw / 9.0
        cell_h = bh / 10.0

    img_out = Path(image_dir) / f"{base_name}.jpg"
    cv2.imwrite(str(img_out), board_bgr)

    yolo_lines = []
    for pt, piece_name in match_result.items():
        class_id = PIECE_CLASS_IDS.get(piece_name, -1)
        if class_id < 0:
            continue
        cx = pt[0] / bw
        cy = pt[1] / bh
        bw_norm = cell_w / bw
        bh_norm = cell_h / bh
        yolo_lines.append(f"{class_id} {cx:.6f} {cy:.6f} {bw_norm:.6f} {bh_norm:.6f}")

    label_out = Path(label_dir) / f"{base_name}.txt"
    label_out.write_text('\n'.join(yolo_lines) + '\n', encoding='utf-8')

    preview = board_bgr.copy()
    for pt, piece_name in match_result.items():
        color = (0, 0, 255) if piece_name.startswith('r') else (0, 0, 0)
        x1 = int(pt[0] - cell_w / 2)
        y1 = int(pt[1] - cell_h / 2)
        x2 = int(pt[0] + cell_w / 2)
        y2 = int(pt[1] + cell_h / 2)
        cv2.rectangle(preview, (x1, y1), (x2, y2), color, 2)
        cv2.putText(preview, piece_name, (x1, max(y1 - 4, 0)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, color, 2)

    preview_out = Path(preview_dir) / f"{base_name}.jpg"
    cv2.imwrite(str(preview_out), preview)

    log.info("完成: %s", base_name)


def process_all(raw_dir, image_dir, label_dir, preview_dir, val_ratio=0.01, max_workers=6):
    raw_path = Path(raw_dir)
    images = sorted([p for p in raw_path.iterdir()
                     if p.suffix.lower() in ('.jpg', '.jpeg', '.png', '.bmp')])

    if not images:
        log.warning("%s 中没有图片文件", raw_dir)
        return

    for d in [image_dir, label_dir, preview_dir]:
        p = Path(d)
        if p.exists():
            shutil.rmtree(p)

    random.seed(42)
    random.shuffle(images)
    split = int(len(images) * (1 - val_ratio))
    train_list = images[:split]
    val_list = images[split:]

    templates = load_templates("template")
    ok = fail = 0
    seq = 0

    for img_list, split_name in [(train_list, "train"), (val_list, "val")]:
        img_dir = Path(str(image_dir), split_name)
        lbl_dir = Path(str(label_dir), split_name)
        prv_dir = Path(str(preview_dir), split_name)
        img_dir.mkdir(parents=True, exist_ok=True)
        lbl_dir.mkdir(parents=True, exist_ok=True)
        prv_dir.mkdir(parents=True, exist_ok=True)

        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            futures = {}
            for img in img_list:
                seq += 1
                base = f"{seq:06d}"
                futures[executor.submit(process_one, str(img), str(img_dir), str(lbl_dir), str(prv_dir), base, templates)] = img.name

            for future in as_completed(futures):
                name = futures[future]
                try:
                    future.result()
                    ok += 1
                except Exception as e:
                    fail += 1
                    log.warning("处理失败: %s - %s", name, e)

    log.info("全部完成: 成功 %d / 失败 %d / 总数 %d (train %d / val %d)",
             ok, fail, len(images), len(train_list), len(val_list))


def main():
    raw_dir = "../model-training/data/raw"
    image_dir = "../model-training/data/images"
    label_dir = "../model-training/data/labels"
    preview_dir = "../model-training/data/preview"
    val_ratio = 0.01

    args = sys.argv[1:]
    if len(args) >= 1: raw_dir = args[0]
    if len(args) >= 2: image_dir = args[1]
    if len(args) >= 3: label_dir = args[2]
    if len(args) >= 4: preview_dir = args[3]
    if len(args) >= 5: val_ratio = float(args[4])

    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s: %(message)s')
    process_all(raw_dir, image_dir, label_dir, preview_dir, val_ratio)


if __name__ == "__main__":
    main()
