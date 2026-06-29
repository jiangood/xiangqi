package io.github.jiangood.xq.opencv;

public interface PieceRecognizer {
    String[][] parseBoard(String imageFile) throws Exception;
}
