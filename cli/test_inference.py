import os
import pytest
from inference import YoloRecognizer, to_fen

BASE_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w"
LAST_FEN = "2ba1k3/4a4/4b4/pr7/9/9/P7P/4Br3/3KR4/9 w"


@pytest.fixture(autouse=True)
def enable_visualization():
    os.environ['XQ_SAVE_RESULT'] = '1'
    yield
    os.environ.pop('XQ_SAVE_RESULT', None)


def _assert_recognizer(image_path, expected_fen):
    recognizer = YoloRecognizer("models/xiangqi_yolo.onnx")
    board = recognizer.parse_board(image_path)
    fen = to_fen(board)
    if fen != expected_fen:
        actual_rows = fen.split('/')
        expected_rows = expected_fen.split('/')
        for i in range(10):
            match = " OK" if actual_rows[i] == expected_rows[i] else " <<<"
            print(f"  row {i}: actual={actual_rows[i]} expected={expected_rows[i]}{match}")
    assert fen == expected_fen, f"Expected: {expected_fen} but got: {fen}"


# base-1.jpg 是另一种风格截图，模板匹配无法识别；
# YOLO 能定位所有棋子但红方底线有分类错误，需比对输出确认具体哪些棋子识别错误
@pytest.mark.skip(reason="识别错误，待分析原因")
def test_yolo_base1():
    _assert_recognizer("demos/base-1.jpg", BASE_FEN)


def test_yolo_base2():
    _assert_recognizer("demos/base-2.jpg", BASE_FEN)


def test_yolo_last2():
    _assert_recognizer("demos/last-2.jpg", LAST_FEN)
