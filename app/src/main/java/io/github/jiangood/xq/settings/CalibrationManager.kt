package io.github.jiangood.xq.settings

import android.content.Context
import io.github.jiangood.xq.opencv.CalibrationData
import io.github.jiangood.xq.opencv.CalibrationTemplate
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import java.io.File

object CalibrationManager {
    private const val DIR_NAME = "calibration"
    private const val META_FILE = "meta.json"
    private const val GRID_FILE = "grid.json"
    private const val INDEX_FILE = "index.json"
    private const val ORIGINAL_FILE = "original.jpg"

    fun getDir(context: Context): File =
        File(context.filesDir, DIR_NAME).also { it.mkdirs() }

    fun isCalibrated(context: Context): Boolean {
        val dir = getDir(context)
        return File(dir, META_FILE).exists() && File(dir, GRID_FILE).exists()
    }

    fun hasOriginalImage(context: Context): Boolean =
        getOriginalImageFile(context).exists()

    fun getOriginalImageFile(context: Context): File =
        File(getDir(context), ORIGINAL_FILE)

    fun save(context: Context, data: CalibrationData) {
        val dir = getDir(context)

        val meta = JSONObject()
        meta.put("imageWidth", data.imageWidth)
        meta.put("imageHeight", data.imageHeight)
        meta.put("cellSize", data.cellSize)
        meta.put("pieceSize", data.pieceSize)
        File(dir, META_FILE).writeText(meta.toString(2), Charsets.UTF_8)

        val gridArr = JSONArray()
        for (r in 0 until 10) {
            val rowArr = JSONArray()
            for (c in 0 until 9) {
                val pt = JSONObject()
                pt.put("x", data.grid[r][c].x)
                pt.put("y", data.grid[r][c].y)
                rowArr.put(pt)
            }
            gridArr.put(rowArr)
        }
        File(dir, GRID_FILE).writeText(gridArr.toString(2), Charsets.UTF_8)

        val idxArr = JSONArray()
        for (tpl in data.templates) {
            val obj = JSONObject()
            obj.put("file", tpl.filename)
            obj.put("type", tpl.pieceType)
            idxArr.put(obj)
        }
        File(dir, INDEX_FILE).writeText(idxArr.toString(2), Charsets.UTF_8)
    }

    fun load(context: Context): CalibrationData? {
        val dir = getDir(context)
        if (!isCalibrated(context)) return null

        return try {
            val data = CalibrationData()

            val meta = JSONObject(File(dir, META_FILE).readText(Charsets.UTF_8))
            data.imageWidth = meta.optInt("imageWidth", 0)
            data.imageHeight = meta.optInt("imageHeight", 0)
            data.cellSize = meta.optDouble("cellSize", 0.0)
            data.pieceSize = meta.optDouble("pieceSize", 0.0)

            val gridArr = JSONArray(File(dir, GRID_FILE).readText(Charsets.UTF_8))
            data.grid = Array(10) { Array(9) { Point() } }
            for (r in 0 until 10) {
                val rowArr = gridArr.getJSONArray(r)
                for (c in 0 until 9) {
                    val pt = rowArr.getJSONObject(c)
                    data.grid[r][c] = Point(pt.optDouble("x", 0.0), pt.optDouble("y", 0.0))
                }
            }

            val idxArr = JSONArray(File(dir, INDEX_FILE).readText(Charsets.UTF_8))
            data.templates = mutableListOf()
            for (i in 0 until idxArr.length()) {
                val obj = idxArr.getJSONObject(i)
                data.templates.add(CalibrationTemplate(obj.getString("file"), obj.getString("type")))
            }

            data
        } catch (e: Exception) {
            null
        }
    }

    fun getTemplateFileDir(context: Context): File = getDir(context)

    fun cropPiece(mat: Mat, grid: Array<Array<Point>>, row: Int, col: Int, pieceSize: Double): Mat? {
        val center = grid[row][col]
        val half = (pieceSize / 2).toInt()
        val x = (center.x - half).toInt()
        val y = (center.y - half).toInt()
        val w = pieceSize.toInt()
        val h = pieceSize.toInt()
        if (x < 0 || y < 0 || x + w > mat.cols() || y + h > mat.rows() || w <= 0 || h <= 0) return null
        return Mat(mat, Rect(x, y, w, h))
    }
}
