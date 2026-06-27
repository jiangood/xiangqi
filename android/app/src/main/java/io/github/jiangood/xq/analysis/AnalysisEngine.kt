package io.github.jiangood.xq.analysis

import android.content.Context
import io.github.jiangood.xq.engine.AndroidEngineClient
import io.github.jiangood.xq.opencv.PieceRecognizer
import io.github.jiangood.xq.opencv.YoloPieceRecognizer
import io.github.jiangood.xq.util.AppLog
import io.github.jiangood.xq.util.FenUtil
import io.github.jiangood.xq.util.NotationConverter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    private val initComplete = CompletableDeferred<Unit>()

    fun init(context: Context) {
        if (initComplete.isCompleted) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppLog.add("[引擎] 初始化 NNUE...")
                val nnueFile = File(context.filesDir, "pikafish.nnue")
                if (!nnueFile.exists()) {
                    try {
                        AppLog.add("[引擎] 解压 NNUE 权重文件...")
                        context.assets.open("pikafish.nnue").use { input ->
                            nnueFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        AppLog.add("[引擎] NNUE 解压完成")
                    } catch (e: Exception) {
                        AppLog.add("[引擎] NNUE 解压跳过: ${e.message}")
                    }
                } else {
                    AppLog.add("[引擎] NNUE 已存在")
                }
                AppLog.add("[引擎] 启动引擎进程...")
                val engine = AndroidEngineClient(context)
                if (engine.start()) {
                    engineClient = engine
                    AppLog.add("[引擎] 引擎启动成功")
                } else {
                    AppLog.add("[引擎] 引擎启动失败")
                }
                AppLog.add("[引擎] 加载 ONNX 模型...")
                val modelFile = File(context.cacheDir, "xiangqi_yolo.onnx")
                if (!modelFile.exists()) {
                    AppLog.add("[引擎] 解压 ONNX 模型...")
                    context.assets.open("xiangqi_yolo.onnx").use { input ->
                        modelFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    AppLog.add("[引擎] ONNX 解压完成")
                } else {
                    AppLog.add("[引擎] ONNX 模型已存在")
                }
                boardRecognizer = YoloPieceRecognizer(modelFile.absolutePath)
                AppLog.add("[引擎] ONNX 模型加载成功")
                initComplete.complete(Unit)
            } catch (e: Exception) {
                AppLog.add("[引擎] 初始化失败: ${e.message}")
                initComplete.completeExceptionally(e)
            }
        }
    }

    suspend fun awaitInitialized() {
        initComplete.await()
    }

    suspend fun analyze(imageFile: File): AnalysisResult? {
        return withContext(Dispatchers.IO) {
            try {
                initComplete.await()
                val recognizer = boardRecognizer
                val engine = engineClient
                if (recognizer == null || engine == null) {
                    AppLog.add("[引擎] 分析失败: recognizer=${recognizer != null}, engine=${engine != null}")
                    return@withContext null
                }
                AppLog.add("[引擎] 开始棋盘识别: ${imageFile.name}")
                val rawBoard = recognizer.parseBoard(imageFile.absolutePath)
                AppLog.add("[引擎] 棋盘识别完成")
                val board = fixBoardOrientation(rawBoard)
                val fen = FenUtil.toFen(board)
                AppLog.add("[引擎] FEN: $fen")
                AppLog.add("[引擎] 引擎分析中...")
                val moves = engine.getTopMoves(fen, 3, 10)
                AppLog.add("[引擎] 引擎返回 ${moves.size} 条走法")
                val chineseMoves = moves.map { NotationConverter.convertToChineseNotation(board, it) }
                AnalysisResult(board, fen, moves, chineseMoves)
            } catch (e: Exception) {
                AppLog.add("[引擎] 分析异常: ${e.message}")
                null
            }
        }
    }

    fun fixBoardOrientation(board: Array<Array<String?>>): Array<Array<String?>> {
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
