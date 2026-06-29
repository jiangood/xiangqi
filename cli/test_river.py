import cv2
import numpy as np
from pathlib import Path
from board_utils import locate_board, detect_grid_lines

DEMOS_DIR = Path(__file__).parent / "demos"
OUTPUT_DIR = DEMOS_DIR / "output"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


def test_grid_lines(image_path):
    stem = image_path.stem
    src_color = cv2.imread(str(image_path))

    src_gray = cv2.cvtColor(src_color, cv2.COLOR_BGR2GRAY)
    board_rect = locate_board(src_gray)
    bx, by, bw, bh = map(int, board_rect)

    board_crop = src_color[by:by+bh, bx:bx+bw].copy()
    gray = cv2.cvtColor(board_crop, cv2.COLOR_BGR2GRAY)
    _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)

    cell_size_prior = bw / 9.0
    h_chain, v_chain = detect_grid_lines(binary, cell_size_prior)

    vis = src_color.copy()
    cv2.rectangle(vis, (bx, by), (bx+bw, by+bh), (255, 0, 0), 2)

    result = ""
    if h_chain is not None and len(h_chain) >= 6:
        spacings = h_chain[1:] - h_chain[:-1]
        cs = np.median(spacings)
        for y in h_chain:
            cv2.line(vis, (bx, by+y), (bx+bw, by+y), (0, 255, 0), 2)
        cell_info = f"cell={cs:.0f} prior={cell_size_prior:.1f}"
        cv2.putText(vis, cell_info, (bx+10, by-10),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 0), 2)
        result = f"PASS: {len(h_chain)} lines, cell={cs:.0f}"
    else:
        cv2.putText(vis, "NO LINES", (bx+10, by+30),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 255), 2)
        result = "FAIL: no horizontal lines"

    if v_chain is not None:
        for x in v_chain:
            cv2.line(vis, (bx+x, by), (bx+x, by+bh), (255, 255, 0), 2)

    out_path = OUTPUT_DIR / f"{stem}-river.jpg"
    cv2.imwrite(str(out_path), vis)
    return result


def main():
    images = sorted(DEMOS_DIR.glob("*.jpg"))
    if not images:
        print("No images found in demos/")
        return

    print(f"Testing grid line detection on {len(images)} images...\n")
    passed = 0
    failed = 0
    for img_path in images:
        try:
            msg = test_grid_lines(img_path)
            print(f"  {img_path.stem}: {msg}")
            if msg.startswith("PASS"):
                passed += 1
            else:
                failed += 1
        except Exception as e:
            print(f"  {img_path.stem}: ERROR: {e}")
            failed += 1

    print(f"\n{passed} passed, {failed} failed out of {len(images)}")


if __name__ == "__main__":
    main()
