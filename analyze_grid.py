import cv2
import numpy as np
import sys

def binarize_board(img):
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)
    if cv2.countNonZero(binary) > binary.size * 0.5:
        binary = cv2.bitwise_not(binary)
    return gray, binary

def analyze(img_path):
    img = cv2.imread(img_path)
    if img is None:
        print(f"ERROR: Cannot load {img_path}")
        return
    h, w = img.shape[:2]
    print(f"Image: {w}x{h}, {img.shape[2]} channels")

    gray, binary = binarize_board(img)
    cv2.imwrite("debug_01_gray.png", gray)
    cv2.imwrite("debug_02_binary.png", binary)

    # Step 1: crop board center
    max_aspect = 1.3
    crop_h = int(w * max_aspect)
    if crop_h < h:
        y = (h - crop_h) // 2
        rough = img[y:y+crop_h, 0:w]
        print(f"Rough crop: y={y}, {rough.shape[1]}x{rough.shape[0]}")
    else:
        rough = img.copy()
        print("No rough crop needed")

    # Step 2: find board rect
    def find_board_rect(src):
        h2, w2 = src.shape[:2]
        min_area = w2 * h2 * 0.08
        edges = cv2.Canny(src, 50, 150)
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
        edges = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, kernel)
        contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

        best_rect = None
        best_score = 0
        for cnt in contours:
            x, y, bw, bh = cv2.boundingRect(cnt)
            area = bw * bh
            if area < min_area:
                continue
            ar = bw / bh
            if ar < 0.5 or ar > 1.6:
                continue
            area_score = area / (w2 * h2)
            ar_score = 1.0 - abs(ar - 0.9) / 1.2
            score = area_score * 0.7 + ar_score * 0.3
            if score > best_score:
                best_score = score
                best_rect = (x, y, bw, bh)

        # fallback OTSU
        if best_rect is None or best_score < 0.15:
            blurred = cv2.GaussianBlur(src, (5, 5), 0)
            _, binary2 = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)
            kernel2 = cv2.getStructuringElement(cv2.MORPH_RECT, (9, 9))
            binary2 = cv2.morphologyEx(binary2, cv2.MORPH_CLOSE, kernel2)
            contours2, _ = cv2.findContours(binary2, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            for cnt in contours2:
                x, y, bw, bh = cv2.boundingRect(cnt)
                area = bw * bh
                if area < min_area:
                    continue
                ar = bw / bh
                if ar < 0.5 or ar > 1.6:
                    continue
                area_score = area / (w2 * h2)
                ar_score = 1.0 - abs(ar - 0.9) / 1.2
                score = area_score * 0.7 + ar_score * 0.3
                if score > best_score:
                    best_score = score
                    best_rect = (x, y, bw, bh)

        if best_score > 0.15:
            return best_rect
        return None

    board_rect = find_board_rect(rough)
    if board_rect:
        x, y, bw, bh = board_rect
        print(f"Board rect: ({x},{y}) {bw}x{bh}, score={x if False else ''}")
        # Mark board rect in debug image
        debug = cv2.cvtColor(binary, cv2.COLOR_GRAY2BGR)
        cv2.rectangle(debug, (x, y), (x+bw, y+bh), (0, 255, 0), 3)
        cv2.imwrite("debug_03_board_rect.png", debug)
    else:
        print("Board rect: NOT FOUND")
        cv2.imwrite("debug_03_no_board.png", binary)

    # Step 3: crop to board
    if board_rect:
        half_piece = max(bw // 18, 4)
        crop_x = max(0, x - half_piece)
        crop_y = max(0, y - half_piece)
        crop_w = min(rough.shape[1] - crop_x, bw + 2 * half_piece)
        crop_h2 = min(rough.shape[0] - crop_y, bh + 2 * half_piece)
        cropped = rough[crop_y:crop_y+crop_h2, crop_x:crop_x+crop_w]
        board_in_crop = (x - crop_x, y - crop_y, bw, bh)
        print(f"Cropped board: {cropped.shape[1]}x{cropped.shape[0]}, board_in_crop={board_in_crop}")
    else:
        cropped = rough
        board_in_crop = None
        print(f"Using rough crop as-is: {cropped.shape[1]}x{cropped.shape[0]}")

    # Step 4: detect grid from lines
    ch = cropped.shape[0]
    cw = cropped.shape[1]
    cell_size_est = max(cw / 9.0, ch / 10.0)
    print(f"Cell size est: {cell_size_est:.1f}")
    if cell_size_est < 8.0:
        print("FATAL: cell too small")
        return

    gray_crop = cv2.cvtColor(cropped, cv2.COLOR_BGR2GRAY)
    _, binary_crop = cv2.threshold(gray_crop, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)
    if cv2.countNonZero(binary_crop) > binary_crop.size * 0.5:
        binary_crop = cv2.bitwise_not(binary_crop)
    cv2.imwrite("debug_04_binary_crop.png", binary_crop)

    def detect_lines(binary, horizontal, cell_size, img_h, img_w):
        expected = 10 if horizontal else 9
        other_dim = img_w if horizontal else img_h

        k_len = max(int(cell_size * 0.80), 3)
        ksize = (k_len, 1) if horizontal else (1, k_len)
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, ksize)
        lines = cv2.erode(binary, kernel)
        lines = cv2.dilate(lines, kernel)

        proj = np.sum(lines > 0, axis=1 if horizontal else 0)
        proj_img = np.zeros((max(200, len(proj)), 400), dtype=np.uint8)
        scale = min(400.0 / max(proj) if max(proj) > 0 else 1.0, 1.0)
        for i, v in enumerate(proj):
            cv2.line(proj_img, (0, i), (int(v * scale), i), 255, 1)
        cv2.imwrite(f"debug_05_{'h' if horizontal else 'v'}_proj.png", proj_img)

        threshold = int(other_dim * 0.15)
        gap = max(int(cell_size * 0.10), 2)
        print(f"  {'H' if horizontal else 'V'} proj: max={int(np.max(proj))}, threshold={threshold}, other_dim={other_dim}")
        above = np.where(proj > threshold)[0]
        print(f"  Above threshold: {len(above)} indices")

        if len(above) < 5:
            print(f"  FAIL: only {len(above)} above-threshold indices (need >=5)")
            return None

        groups = []
        start = above[0]
        for i in range(1, len(above)):
            if above[i] - above[i-1] > gap:
                groups.append((start + above[i-1]) // 2)
                start = above[i]
        groups.append((start + above[-1]) // 2)
        print(f"  Groups: {len(groups)}: {groups[:30]}{'...' if len(groups) > 30 else ''}")

        if len(groups) < 5:
            print(f"  FAIL: too few groups ({len(groups)})")
            return None

        # Find chain
        min_chain = 7 if horizontal else 6
        best_chain = None
        err_pixels = cell_size * 0.20

        for i in range(len(groups) - 1):
            d = groups[i+1] - groups[i]
            if abs(d / cell_size - 1.0) >= 0.20:
                continue
            chain = [groups[i], groups[i+1]]
            expected_pos = groups[i+1] + d
            for k in range(i+2, len(groups)):
                if abs(groups[k] - expected_pos) <= err_pixels:
                    chain.append(groups[k])
                    expected_pos = groups[k] + d
                elif groups[k] > expected_pos + err_pixels:
                    break
            expected_pos = groups[i] - d
            for k in range(i-1, -1, -1):
                if abs(groups[k] - expected_pos) <= err_pixels:
                    chain.insert(0, groups[k])
                    expected_pos = groups[k] - d
                elif groups[k] < expected_pos - err_pixels:
                    break
            if len(chain) > len(best_chain or []):
                best_chain = chain

        if best_chain is None or len(best_chain) < min_chain:
            print(f"  FAIL: best chain too short ({len(best_chain) if best_chain else 0}/{expected})")
            return None

        best_chain.sort()
        print(f"  Chain: {len(best_chain)}/{expected} lines: {best_chain}")
        return best_chain

    h_pos = detect_lines(binary_crop, True, cell_size_est, ch, cw)
    v_pos = detect_lines(binary_crop, False, cell_size_est, ch, cw)

    if h_pos is None or v_pos is None:
        print("\n*** GRID DETECTION FAILED ***")
    else:
        # Build and validate grid
        grid = np.zeros((10, 9, 2))
        for r in range(10):
            for c in range(9):
                grid[r, c] = [v_pos[c], h_pos[r]]
        print(f"\nGrid built: 10x9, cell_h={h_pos[1]-h_pos[0]:.1f}, cell_w={v_pos[1]-v_pos[0]:.1f}")

        # Visualize
        vis = cropped.copy()
        for r in range(10):
            for c in range(9):
                cv2.circle(vis, (int(grid[r,c,0]), int(grid[r,c,1])), 3, (0, 0, 255), -1)
        cv2.imwrite("debug_06_grid.png", vis)
        print("Grid visualization saved to debug_06_grid.png")

    # Check the crop board center step's result
    print("\n--- findBoardRect analysis on original ---")
    board_rect2 = find_board_rect(img)
    if board_rect2:
        x, y, bw, bh = board_rect2
        ar = bw / bh
        print(f"Board rect on original: ({x},{y}) {bw}x{bh}, ar={ar:.2f}")
        vis2 = img.copy()
        cv2.rectangle(vis2, (x, y), (x+bw, y+bh), (0, 255, 0), 3)
        cv2.imwrite("debug_00_board_orig.png", vis2)
    else:
        print("No board rect found on original image")

if __name__ == "__main__":
    img_path = sys.argv[1] if len(sys.argv) > 1 else r"D:\ws\xiangqi\screenshot_fail.jpg"
    analyze(img_path)
