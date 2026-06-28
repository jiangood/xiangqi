import cv2
import numpy as np
from pathlib import Path
from board_utils import locate_board, calibrate_grid

DEMOS_DIR = Path(__file__).parent / "demos"
OUTPUT_DIR = DEMOS_DIR / "output"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


def draw_grid(image_path):
    src_color = cv2.imread(str(image_path))
    src_gray = cv2.cvtColor(src_color, cv2.COLOR_BGR2GRAY)

    board_rect = locate_board(src_gray)
    bx, by, bw, bh = map(int, board_rect)

    board_crop = src_color[by:by + bh, bx:bx + bw].copy()
    gray = cv2.cvtColor(board_crop, cv2.COLOR_BGR2GRAY)
    _, binary_img = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)

    grid = calibrate_grid({}, board_rect, binary_img, src_color.shape[:2][::-1])

    vis = src_color.copy()
    cv2.rectangle(vis, (bx, by), (bx + bw, by + bh), (255, 0, 0), 2)

    cell_size = grid[0][1][0] - grid[0][0][0]

    # horizontal lines
    for r in range(10):
        x1 = int(grid[r][0][0])
        x2 = int(grid[r][8][0])
        y = int(grid[r][0][1])
        cv2.line(vis, (x1, y), (x2, y), (0, 255, 0) if r < 5 else (0, 255, 255), 2)

    # vertical lines
    for c in range(9):
        x = int(grid[0][c][0])
        y1 = int(grid[0][c][1])
        y2 = int(grid[9][c][1])
        cv2.line(vis, (x, y1), (x, y2), (0, 200, 200), 2)

    label = f"cell={cell_size:.1f}"
    cv2.putText(vis, label, (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 0), 2)

    out_path = OUTPUT_DIR / f"{image_path.stem}-grid.jpg"
    cv2.imwrite(str(out_path), vis)
    print(f"Saved: {out_path}  (cell={cell_size:.1f})")
    return grid


def main():
    images = sorted(DEMOS_DIR.glob("*.jpg"))
    if not images:
        print("No images found in demos/")
        return

    print(f"Processing {len(images)} images...\n")
    for img_path in images:
        try:
            draw_grid(img_path)
        except Exception as e:
            print(f"Failed: {img_path}  ({e})")


if __name__ == "__main__":
    main()
