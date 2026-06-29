#!/usr/bin/env python3
"""
Grid alignment diagnostic: compare detected lines vs computed grid at each row.
"""
import cv2
import numpy as np
from pathlib import Path
from board_utils import locate_board, calibrate_grid, detect_grid_lines

DEMOS_DIR = Path(__file__).parent / "demos"
OUTPUT_DIR = DEMOS_DIR / "output"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


def inspect_grid(image_path):
    stem = image_path.stem
    src_color = cv2.imread(str(image_path))
    src_gray = cv2.cvtColor(src_color, cv2.COLOR_BGR2GRAY)
    h, w = src_gray.shape

    board_rect = locate_board(src_gray)
    bx, by, bw, bh = map(int, board_rect)
    print(f"=== {stem} ===")
    print(f"board_rect: ({bx}, {by}, {bw}, {bh})")
    print(f"Image size: {w}x{h}")

    # Crop
    board_crop = src_color[by:by + bh, bx:bx + bw].copy()
    gray = cv2.cvtColor(board_crop, cv2.COLOR_BGR2GRAY)
    _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)

    cell_size_prior = bw / 9.0
    h_chain, v_chain = detect_grid_lines(binary, cell_size_prior)

    grid = calibrate_grid({}, board_rect, binary, src_color.shape[:2][::-1])

    # Cell size from grid
    cell_h = grid[1][0][1] - grid[0][0][1]
    cell_w = grid[0][1][0] - grid[0][0][0]
    print(f"Grid cell: {cell_w:.2f} x {cell_h:.2f}")

    print(f"\nDetected h_chain: {h_chain}")
    if h_chain is not None:
        for i, y in enumerate(h_chain):
            # Convert detected chain (crop-relative) to full-image coords
            abs_y = by + y
            print(f"  chain[{i}] = {y} (crop-relative) -> {abs_y} (abs)")

    print(f"\nGrid rows (full-image Y):")
    for r in range(10):
        y = grid[r][0][1]
        x = grid[r][0][0]
        # Check how far this grid row is from nearest detected line
        if h_chain is not None:
            deltas = [abs(y - (by + hc)) for hc in h_chain]
            nearest = min(deltas)
            nearest_idx = deltas.index(nearest)
            detected_val = by + h_chain[nearest_idx]
            print(f"  row[{r}] y={y:.1f} x={x:.1f}  nearest_chain[{nearest_idx}]={detected_val}  diff={nearest:.2f}")
        else:
            print(f"  row[{r}] y={y:.1f} x={x:.1f}  (no chain)")

    print(f"\nGrid columns (full-image X):")
    if v_chain is not None:
        for i, x in enumerate(v_chain):
            abs_x = bx + x
            print(f"  chain[{i}] = {x} (crop-relative) -> {abs_x} (abs)")
    for c in range(9):
        x = grid[0][c][0]
        if v_chain is not None:
            deltas = [abs(x - (bx + vc)) for vc in v_chain]
            nearest = min(deltas)
            nearest_idx = deltas.index(nearest)
            detected_val = bx + v_chain[nearest_idx]
            print(f"  col[{c}] x={x:.1f}  nearest_chain[{nearest_idx}]={detected_val}  diff={nearest:.2f}")
        else:
            print(f"  col[{c}] x={x:.1f}  (no chain)")

    # Generate diagnostic overlay
    vis = src_color.copy()
    cv2.rectangle(vis, (bx, by), (bx + bw, by + bh), (255, 0, 0), 2)

    # Draw grid rows with labels showing diff
    for r in range(10):
        y = int(grid[r][0][1])
        color = (0, 255, 0) if r < 5 else (0, 255, 255)
        x1 = int(grid[r][0][0])
        x2 = int(grid[r][8][0])
        cv2.line(vis, (x1, y), (x2, y), color, 2)
        cv2.putText(vis, str(r), (x1 - 20, y + 4), cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 1)

    # Draw detected chain lines (in red, 1px) for comparison
    if h_chain is not None:
        for i, yc in enumerate(h_chain):
            abs_yc = by + yc
            cv2.line(vis, (bx, abs_yc), (bx + bw, abs_yc), (0, 0, 255), 1)
            cv2.putText(vis, f"c{i}", (bx - 40, abs_yc + 4), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (0, 0, 255), 1)

    label = f"cell={cell_h:.1f}  grid_demo"
    cv2.putText(vis, label, (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 0), 2)

    out_path = OUTPUT_DIR / f"{stem}-debug.jpg"
    cv2.imwrite(str(out_path), vis)
    print(f"\nSaved: {out_path}")
    print()


def main():
    images = sorted(DEMOS_DIR.glob("*.jpg"))
    if not images:
        print("No images found in demos/")
        return
    for img_path in images:
        try:
            inspect_grid(img_path)
        except Exception as e:
            print(f"ERROR {img_path.name}: {e}")

if __name__ == "__main__":
    main()
