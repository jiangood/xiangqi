package io.github.jiangood.xq.analysis

import android.content.Context
import io.github.jiangood.xq.engine.AndroidEngineClient
import io.github.jiangood.xq.opencv.PieceRecognizer
import io.github.jiangood.xq.opencv.YoloPieceRecognizer
import io.github.jiangood.xq.util.FenUtil
import io.github.jiangood.xq.util.NotationConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class AnalysisResult(
    val board: Array<Array<String?>>,
    val fen: String,
    val standardMoves: List<String>,
    val chineseMoves: List<String>
)

object AnalysisEngine {
    var engineClient: AndroidEngineClient? = null
        private set
    var boardRecognizer: PieceRecognizer? = null
        private set

    suspend fun init(context: Context) {
        withContext(Dispatchers.IO) {
            val nnueFile = File(context.filesDir, "pikafish.nnue")
            if (!nnueFile.exists()) {
                try {
                    context.assets.open("pikafish.nnue").use { input ->
                        nnueFile.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (_: Exception) {}
            }
            val engine = AndroidEngineClient(context)
            if (engine.start()) {
                engineClient = engine
            }
            try {
                val modelFile = File(context.cacheDir, "xiangqi_yolo.onnx")
                if (!modelFile.exists()) {
                    context.assets.open("xiangqi_yolo.onnx").use { input ->
                        modelFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                boardRecognizer = YoloPieceRecognizer(modelFile.absolutePath)
            } catch (_: Exception) {}
        }
    }

    suspend fun analyze(context: Context, imageFile: File): AnalysisResult? {
        return withContext(Dispatchers.IO) {
            try {
                val recognizer = boardRecognizer
                val engine = engineClient
                if (recognizer == null || engine == null) return@withContext null
                val rawBoard = recognizer.parseBoard(imageFile.absolutePath)
                val board = fixBoardOrientation(rawBoard)
                val fen = FenUtil.toFen(board)
                val moves = engine.getTopMoves(fen, 3, 10)
                val chineseMoves = moves.map { NotationConverter.convertToChineseNotation(board, it) }
                AnalysisResult(board, fen, moves, chineseMoves)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun fixBoardOrientation(board: Array<Array<String?>>): Array<Array<String?>> {
        var blackTop = false
        for (i in 0..2) {
            for (j in 3..5) {
                if (board[i][j] == "bk") blackTop = true
            }
        }
        if (!blackTop) {
            for (i in board.indices) {
                for (j in board[i].indices) {
                    val p = board[i][j]
                    if (p != null) {
                        board[i][j] = if (p[0] == 'r') "b${p[1]}" else "r${p[1]}"
                    }
                }
            }
        }
        return board
    }

    fun release() {
        engineClient?.close()
        engineClient = null
        boardRecognizer = null
    }
}
