package io.github.jiangood.xq.opencv;

public class CalibrationTemplate {
    public String filename;
    public String pieceType;

    public CalibrationTemplate() {}

    public CalibrationTemplate(String filename, String pieceType) {
        this.filename = filename;
        this.pieceType = pieceType;
    }
}
