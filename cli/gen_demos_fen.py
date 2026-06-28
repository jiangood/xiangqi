#!/usr/bin/env python3
"""Batch process demos/ images, output per-image FEN + board to demos/*.txt"""
from pathlib import Path
import sys
import logging

from inference import YoloRecognizer, to_fen, is_black_top, convert_red_black, validate_position

logging.basicConfig(level=logging.WARNING, format='%(asctime)s %(levelname)s: %(message)s')

DEMOS_DIR = Path(__file__).parent / 'demos'
IMAGE_EXTS = {'.jpg', '.jpeg', '.png', '.bmp', '.tiff'}

PIECE_CHINESE = {
    "rk": "帅", "ra": "仕", "rb": "相", "rr": "車", "rn": "馬", "rc": "炮", "rp": "兵",
    "bk": "将", "ba": "士", "bb": "象", "br": "车", "bn": "马", "bc": "炮", "bp": "卒",
}
STANDARD_OPENING_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w"


def board_to_str(board):
    lines = []
    for i in range(10):
        lines.append(' '.join(PIECE_CHINESE.get(p, '＋') if p else '＋' for p in board[i]))
    lines.append("")
    lines.append("红方：帅 仕 相 車 馬 炮 兵")
    lines.append("黑方：将 士 象 车 马 炮 卒")
    return '\n'.join(lines)


def count_pieces(board):
    red = black = 0
    red_kings = black_kings = 0
    for row in board:
        for cell in row:
            if cell and len(cell) == 2:
                if cell[0] == 'r':
                    red += 1
                    if cell[1] == 'k':
                        red_kings += 1
                else:
                    black += 1
                    if cell[1] == 'k':
                        black_kings += 1
    return red, black, red_kings, black_kings


def verify(img_name, board, fen):
    issues = []

    red, black, rk, bk = count_pieces(board)
    total = red + black

    if total == 0:
        issues.append("未检测到任何棋子")
    if rk != 1:
        issues.append(f"红方将/帅数量={rk}(应为1)")
    if bk != 1:
        issues.append(f"黑方将/帅数量={bk}(应为1)")
    if total > 32:
        issues.append(f"棋子总数={total}(不应超过32)")

    stem = Path(img_name).stem
    is_opening = stem.endswith('.0') or stem.endswith('_0') or stem.endswith('-0')
    if is_opening and fen != STANDARD_OPENING_FEN:
        issues.append(f"开局图片但FEN与标准开局不匹配")
    elif is_opening and not issues:
        issues.append("[OK] 标准开局，验证通过")

    if not issues:
        issues.append("[OK] 验证通过")

    return issues


def main():
    recognizer = YoloRecognizer(str(Path(__file__).parent / 'models/xiangqi_yolo.onnx'))

    image_files = sorted(f for f in DEMOS_DIR.iterdir() if f.suffix.lower() in IMAGE_EXTS)
    if not image_files:
        print("No images found in demos/", file=sys.stderr)
        sys.exit(1)

    has_errors = False
    for img_path in image_files:
        board = recognizer.parse_board(str(img_path))

        black_top = is_black_top(board)
        if not black_top:
            convert_red_black(board)

        warnings = validate_position(board)
        if warnings:
            for w in warnings:
                logging.warning("%s: %s", img_path.name, w)

        fen = to_fen(board)
        board_str = board_to_str(board)

        txt_path = img_path.with_suffix('.txt')
        direction = "黑方在上（顶部），红方在下（底部）" if black_top else "红方在上（顶部），黑方在下（底部）"
        content = (f"{fen}\n{'=' * 40}\n{board_str}\n\n"
                   f"方向：{direction}\n\n"
                   f"FEN说明：大写=红方 小写=黑方 | "
                   f"K/k=将帅 A/a=仕士 B/b=相象\n"
                   f"R/r=车 N/n=马 C/c=炮 P/p=兵卒 | "
                   f"数字=连续空格数 /=换行 w=红方走\n")
        txt_path.write_text(content, encoding='utf-8')

        checks = verify(img_path.name, board, fen)
        if not any(c.startswith("[OK]") for c in checks):
            has_errors = True

        print(f"  [{img_path.name}] -> {txt_path.name}")
        for c in checks:
            print(f"    {c}")

    if not has_errors:
        print("All checks passed.", file=sys.stderr)
    else:
        print("Some checks FAILED - review warnings above.", file=sys.stderr)


if __name__ == "__main__":
    main()
