import cv2
import numpy as np
import os
import sys

STYLE1 = os.path.join("test-images", "style1.jpg")
STYLE2 = os.path.join("test-images", "style2.jpg")
OUT_DIR = "templates"
PIECE_DISPLAY_ORDER = ["rk","ra","rb","rr","rn","rc","rp","bk","ba","bb","br","bn","bc","bp"]

PIECE_CHINESE = {
    "rk":"帅","ra":"仕","rb":"相","rr":"車","rn":"馬","rc":"炮","rp":"兵",
    "bk":"将","ba":"士","bb":"象","br":"车","bn":"马","bc":"炮","bp":"卒"
}

STANDARD_OPENING = [
    ["br","bn","bb","ba","bk","ba","bb","bn","br"],
    [None,None,None,None,None,None,None,None,None],
    [None,"bc",None,None,None,None,None,"bc",None],
    ["bp",None,"bp",None,"bp",None,"bp",None,"bp"],
    [None,None,None,None,None,None,None,None,None],
    [None,None,None,None,None,None,None,None,None],
    ["rp",None,"rp",None,"rp",None,"rp",None,"rp"],
    [None,"rc",None,None,None,None,None,"rc",None],
    [None,None,None,None,None,None,None,None,None],
    ["rr","rn","rb","ra","rk","ra","rb","rn","rr"]
]

MATCH_THRESHOLD = 0.65

def crop_board_center(img):
    h, w = img.shape[:2]
    crop_h = int(w * 10.0 / 9.0)
    if crop_h >= h:
        return img.copy()
    y = (h - crop_h) // 2
    return img[y:y+crop_h, 0:w].copy()

def detect_grid_lines(binary, cell_size):
    h, w = binary.shape
    fg = binary.copy()
    if cv2.countNonZero(binary) > w * h * 0.5:
        fg = cv2.bitwise_not(binary)
    k_len = max(int(cell_size * 0.8), 1)
    gap = int(cell_size * 0.1)
    h_th = int(w * 0.15)
    h_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (k_len, 1))
    h_lines = cv2.erode(fg, h_kernel)
    h_lines = cv2.dilate(h_lines, h_kernel)
    h_cnt = np.sum(h_lines > 0, axis=1).astype(int)
    h_groups = extract_groups(h_cnt, h_th, gap)
    h_chain = find_best_line_chain(h_groups, cell_size, 0.2, 4) if h_groups is not None else None
    v_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (1, k_len))
    v_lines = cv2.erode(fg, v_kernel)
    v_lines = cv2.dilate(v_lines, v_kernel)
    v_cnt = np.sum(v_lines > 0, axis=0).astype(int)
    v_th = int(h * 0.15)
    v_groups = extract_groups(v_cnt, v_th, gap)
    v_chain = find_best_line_chain(v_groups, cell_size, 0.2, 4) if v_groups is not None else None
    return h_chain, v_chain

def extract_groups(counts, threshold, gap):
    above = [i for i, c in enumerate(counts) if c > threshold]
    if len(above) < 5:
        return None
    groups = []
    start = above[0]
    for i in range(1, len(above)):
        if above[i] - above[i-1] > gap:
            groups.append((start + above[i-1]) // 2)
            start = above[i]
    groups.append((start + above[-1]) // 2)
    return np.array(groups)

def find_best_line_chain(groups, cell_size, max_err, min_chain):
    if groups is None or len(groups) < 2:
        return None
    n = len(groups)
    best_chain = []
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
    if len(best_chain) < min_chain:
        return None
    return np.array(best_chain)

def chain_to_uniform(chain, expected_n, known_center):
    n = len(chain)
    spacings = np.diff(chain)
    spacings.sort()
    cs = spacings[len(spacings) // 2]
    if n == expected_n:
        return chain.astype(float)
    center_idx = (expected_n - 1) / 2.0
    origin = known_center - center_idx * cs
    return origin + np.arange(expected_n) * cs

def compute_grid(img):
    h, w = img.shape[:2]
    board_rect = (0, 0, w, h)
    bw, bh = w, h
    center_x = bw / 2.0
    center_y = bh / 2.0
    cell_size_est = w / 9.0
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)
    h_chain, v_chain = detect_grid_lines(binary, cell_size_est)
    if h_chain is not None and len(h_chain) >= 6:
        h_uniform = chain_to_uniform(h_chain, 10, center_y)
        if v_chain is not None and len(v_chain) >= 4:
            v_uniform = chain_to_uniform(v_chain, 9, center_x)
        else:
            cs_h = h_uniform[1] - h_uniform[0]
            v_uniform = np.array([center_x - 4*cs_h + c*cs_h for c in range(9)])
    else:
        border_ratio = 0.05
        margin = int(min(bw, bh) * border_ratio)
        grid_left = margin
        grid_top = margin
        grid_width = bw - 2*margin
        grid_height = bh - 2*margin
        cell_w = grid_width / 8.0
        cell_h = grid_height / 9.0
        h_uniform = np.array([grid_top + r*cell_h for r in range(10)])
        v_uniform = np.array([grid_left + c*cell_w for c in range(9)])
    grid = np.zeros((10, 9, 2), dtype=np.float32)
    for r in range(10):
        for c in range(9):
            grid[r, c] = [v_uniform[c], h_uniform[r]]
    return grid

def detect_piece_center(roi_gray):
    """Detect piece circle center in an ROI. Returns (dx, dy) offset from ROI center."""
    h, w = roi_gray.shape
    roi_cx, roi_cy = w // 2, h // 2
    circles = cv2.HoughCircles(roi_gray, cv2.HOUGH_GRADIENT, dp=1.2,
                                minDist=max(w,h)//2,
                                param1=50, param2=20,
                                minRadius=10, maxRadius=max(w,h)//2)
    if circles is not None:
        circles = np.round(circles[0,:]).astype(int)
        best = min(circles, key=lambda c: (c[0]-roi_cx)**2 + (c[1]-roi_cy)**2)
        return best[0] - roi_cx, best[1] - roi_cy
    return None

def crop_piece_aligned(img, gray, grid, row, col, cell_size, piece_scale=0.65):
    """Crop a piece at (row,col) using circle detection to center correctly."""
    cx, cy = int(grid[row, col, 0]), int(grid[row, col, 1])
    # Search ROI for circle detection
    search_r = int(cell_size * 0.45)
    x1 = max(0, cx - search_r)
    x2 = min(img.shape[1], cx + search_r)
    y1 = max(0, cy - search_r)
    y2 = min(img.shape[0], cy + search_r)
    roi_gray = gray[y1:y2, x1:x2]
    offset = detect_piece_center(roi_gray)
    if offset is not None:
        dx, dy = offset
        cx += dx
        cy += dy
    piece_size = cell_size * piece_scale
    half = int(piece_size / 2)
    x = max(0, int(cx - half))
    y = max(0, int(cy - half))
    w = h = int(piece_size)
    if x + w > img.shape[1] or y + h > img.shape[0]:
        return None
    return img[y:y+h, x:x+w].copy()

def extract_templates():
    print("=== 步骤1: 加载开局图-样式1.jpg ===")
    img = cv2.imread(STYLE1)
    if img is None:
        print("ERROR: 无法加载", STYLE1)
        sys.exit(1)
    print(f"  原始尺寸: {img.shape[1]}x{img.shape[0]}")
    cropped = crop_board_center(img)
    print(f"  裁切后: {cropped.shape[1]}x{cropped.shape[0]}")
    gray = cv2.cvtColor(cropped, cv2.COLOR_BGR2GRAY)
    _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)
    h_chain, v_chain = detect_grid_lines(binary, cropped.shape[1] / 9.0)
    print(f"  检测到水平线: {len(h_chain) if h_chain is not None else 0}")
    print(f"  检测到垂直线: {len(v_chain) if v_chain is not None else 0}")
    grid = compute_grid(cropped)
    cell_size = grid[1,0,1] - grid[0,0,1]
    print(f"  网格间距: {cell_size:.1f}px")
    piece_size = cell_size * 0.65
    print(f"  棋子大小: {piece_size:.1f}px")
    os.makedirs(OUT_DIR, exist_ok=True)
    unique_types = set()
    for r in range(10):
        for c in range(9):
            pt = STANDARD_OPENING[r][c]
            if pt and pt not in unique_types:
                unique_types.add(pt)
    print(f"\n=== 步骤2: 裁切 {len(unique_types)} 个棋子模板（圆检测对齐）===")
    saved = []
    for piece_type in PIECE_DISPLAY_ORDER:
        found = False
        for r in range(10):
            for c in range(9):
                if STANDARD_OPENING[r][c] == piece_type:
                    piece_img = crop_piece_aligned(cropped, gray, grid, r, c, cell_size)
                    if piece_img is not None:
                        piece_gray = cv2.cvtColor(piece_img, cv2.COLOR_BGR2GRAY)
                        fname = f"{piece_type}.png"
                        cv2.imwrite(os.path.join(OUT_DIR, fname), piece_gray)
                        ch = PIECE_CHINESE.get(piece_type, piece_type)
                        print(f"  {piece_type:4s} ({ch})  -> {fname}  ({piece_img.shape[1]}x{piece_img.shape[0]})")
                        saved.append(piece_type)
                        found = True
                    break
            if found:
                break
    print(f"\n成功保存 {len(saved)} 个模板到 {OUT_DIR}/")
    return saved

def match_template_peak(src_gray, template):
    result = cv2.matchTemplate(src_gray, template, cv2.TM_CCOEFF_NORMED)
    h_r, w_r = result.shape
    sup_r = max(template.shape[1], template.shape[0]) // 2
    matches = []
    while True:
        min_val, max_val, min_loc, max_loc = cv2.minMaxLoc(result)
        if max_val < MATCH_THRESHOLD:
            break
        matches.append({
            'point': (max_loc[0] + template.shape[1] // 2, max_loc[1] + template.shape[0] // 2),
            'score': max_val
        })
        x0 = max(0, max_loc[0] - sup_r)
        y0 = max(0, max_loc[1] - sup_r)
        x1 = min(w_r, max_loc[0] + sup_r)
        y1 = min(h_r, max_loc[1] + sup_r)
        result[y0:y1, x0:x1] = -1
    return matches

def assign_pieces_to_grid(matches, grid):
    board = [[None]*9 for _ in range(10)]
    if not matches:
        return board
    cell_r = max(grid[1,0,1] - grid[0,0,1], grid[0,1,0] - grid[0,0,0]) / 3.0
    for pt, piece_type in matches:
        best_dist = float('inf')
        best_r, best_c = -1, -1
        for r in range(10):
            for c in range(9):
                dx = pt[0] - grid[r,c,0]
                dy = pt[1] - grid[r,c,1]
                dist = (dx*dx + dy*dy) ** 0.5
                if dist < best_dist:
                    best_dist = dist
                    best_r, best_c = r, c
        if best_dist <= cell_r and best_r >= 0 and best_c >= 0 and board[best_r][best_c] is None:
            board[best_r][best_c] = piece_type
    return board

def board_to_fen(board):
    fen_rows = []
    for r in range(10):
        row_buf = []
        empty = 0
        for c in range(9):
            p = board[r][c]
            if p is None or p.strip() == '':
                empty += 1
            else:
                if empty > 0:
                    row_buf.append(str(empty))
                    empty = 0
                row_buf.append(p[1].upper() if p[0] == 'r' else p[1])
        if empty > 0:
            row_buf.append(str(empty))
        fen_rows.append(''.join(row_buf))
    return '/'.join(fen_rows)

def is_red_bottom(board):
    rk_row = bk_row = -1
    for r in range(10):
        if r > 2 and r < 7:
            continue
        for c in range(3, 6):
            p = board[r][c]
            if p == 'rk':
                rk_row = r
            elif p == 'bk':
                bk_row = r
    if rk_row == -1 or bk_row == -1:
        return True
    return rk_row > bk_row

def count_pieces(board):
    return sum(1 for r in range(10) for c in range(9) if board[r][c] is not None)

def test_recognition(template_dir):
    print("\n=== 步骤3: 测试识别 开局图-样式2.jpg ===")
    img = cv2.imread(STYLE2)
    if img is None:
        print("ERROR: 无法加载", STYLE2)
        return None
    print(f"  原始尺寸: {img.shape[1]}x{img.shape[0]}")
    cropped = crop_board_center(img)
    print(f"  裁切后: {cropped.shape[1]}x{cropped.shape[0]}")
    grid = compute_grid(cropped)
    cell_size = grid[1,0,1] - grid[0,0,1]
    print(f"  网格间距: {cell_size:.1f}px")
    src_gray = cv2.cvtColor(cropped, cv2.COLOR_BGR2GRAY)
    all_matches = []
    for fname in os.listdir(template_dir):
        if not fname.endswith('.png'):
            continue
        piece_type = fname.replace('.png', '')
        tmpl = cv2.imread(os.path.join(template_dir, fname), cv2.IMREAD_GRAYSCALE)
        if tmpl is None:
            continue
        matches = match_template_peak(src_gray, tmpl)
        for m in matches:
            all_matches.append((m['point'], piece_type, m['score']))
    all_matches.sort(key=lambda x: -x[2])
    seen = set()
    filtered = []
    for pt, pt_type, score in all_matches:
        key = (round(pt[0]), round(pt[1]))
        if key not in seen:
            seen.add(key)
            filtered.append((pt, pt_type))
    print(f"  检测到 {len(filtered)} 个匹配 (阈值>{MATCH_THRESHOLD})")
    board = assign_pieces_to_grid(filtered, grid)
    piece_count = count_pieces(board)
    print(f"  棋盘棋子数: {piece_count}")
    active = 'w' if is_red_bottom(board) else 'b'
    fen = board_to_fen(board) + f" {active}"
    print(f"\n  FEN: {fen}")
    total = correct = 0
    mismatches = []
    for r in range(10):
        for c in range(9):
            expected = STANDARD_OPENING[r][c]
            actual = board[r][c]
            if expected is None and actual is None:
                continue
            total += 1
            if expected == actual:
                correct += 1
            else:
                mismatches.append(f"  ({r},{c}): 期望={PIECE_CHINESE.get(expected,'空') if expected else '空'}, 识别={PIECE_CHINESE.get(actual,'空') if actual else '空'}")
    print(f"\n  识别准确率: {correct}/{total} = {correct*100.0/total:.1f}%")
    if mismatches:
        print(f"\n  错误详情 ({len(mismatches)}):")
        for m in mismatches:
            print(m)
    else:
        print("  全部正确!")
    return fen

if __name__ == "__main__":
    saved = extract_templates()
    if saved:
        test_recognition(OUT_DIR)
