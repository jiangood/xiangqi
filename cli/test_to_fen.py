import sys
import os
sys.path.insert(0, os.path.dirname(__file__))

from inference import to_fen


def test_fen_red_at_bottom():
    board = [[None]*9 for _ in range(10)]
    board[9][4] = "rk"
    board[0][4] = "bk"
    fen = to_fen(board)
    assert fen.endswith(" w"), f"expected ' w' suffix, got: {fen}"


def test_fen_black_at_bottom():
    board = [[None]*9 for _ in range(10)]
    board[9][4] = "bk"
    board[0][4] = "rk"
    fen = to_fen(board)
    assert fen.endswith(" b"), f"expected ' b' suffix, got: {fen}"


def test_fen_red_at_bottom_complex_board():
    board = [[None]*9 for _ in range(10)]
    board[0][0] = "br"
    board[0][1] = "bn"
    board[9][0] = "rr"
    board[9][4] = "rk"
    board[0][4] = "bk"
    fen = to_fen(board)
    assert fen.endswith(" w"), f"expected ' w', got: {fen}"


def test_fen_black_at_bottom_complex_board():
    board = [[None]*9 for _ in range(10)]
    board[9][0] = "br"
    board[9][4] = "bk"
    board[0][4] = "rk"
    fen = to_fen(board)
    assert fen.endswith(" b"), f"expected ' b', got: {fen}"


def test_fen_no_king_reverts_to_w():
    board = [[None]*9 for _ in range(10)]
    board[9][0] = "rr"
    board[0][0] = "br"
    fen = to_fen(board)
    assert fen.endswith(" w"), f"expected ' w' fallback, got: {fen}"


def test_fen_rk_in_last_three_rows_preferred():
    """Find k in bottom 3 rows first (row 7-9)."""
    board = [[None]*9 for _ in range(10)]
    board[9][0] = "br"
    board[7][4] = "bk"
    board[0][4] = "rk"
    fen = to_fen(board)
    assert fen.endswith(" b"), f"expected ' b' (bk in row 7), got: {fen}"


def test_fen_backward_compatible_empty_board():
    board = [[None]*9 for _ in range(10)]
    fen = to_fen(board)
    assert fen == "9/9/9/9/9/9/9/9/9/9 w", f"unexpected: {fen}"
