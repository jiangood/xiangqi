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
                    } catch (e: java.io.FileNotFoundException) {
                        AppLog.add("[引擎] NNUE 权重文件不存在（thin 包），请先安装完整版")
                    } catch (e: Exception) {
                        AppLog.add("[引擎] NNUE 解压跳过: ${e.message}")
                    }
                } else {
                    AppLog.add("[引擎] NNUE 已存在")
                }
                if (!nnueFile.exists()) {
                    AppLog.add("[引擎] NNUE 权重文件缺失，跳过引擎启动")
                    initComplete.complete(Unit)
                    return@launch
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
                val modelFile = File(context.filesDir, "xiangqi_yolo.onnx")
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
                AppLog.add("[引擎] 棋盘识别完成, 检测到棋子: ${countPieces(rawBoard)}")
                val board = rawBoard
                logBoard(board)
                val fen = FenUtil.toFen(board)
                AppLog.add("[引擎] FEN: $fen")
                AppLog.add("[引擎] 引擎分析中...")
                val moves = engine.getBestMove(fen, io.github.jiangood.xq.settings.SettingsManager.getDepth())
                AppLog.add("[引擎] 引擎返回 ${moves.size} 条走法: ${moves.joinToString(", ")}")
                val chineseMoves = moves.map { move ->
                    val result = NotationConverter.convertToChineseNotation(board, move)
                    AppLog.add("[引擎] 翻译: $move -> $result")
                    result
                }
                AnalysisResult(board, fen, moves, chineseMoves)
            } catch (e: Exception) {
                AppLog.add("[引擎] 分析异常: ${e.message}")
                null
            }
        }
    }

    private fun countPieces(board: Array<Array<String?>>): Int {
        var count = 0
        for (i in board.indices) {
            for (j in board[i].indices) {
                if (board[i][j] != null) count++
            }
        }
        return count
    }

    private fun logBoard(board: Array<Array<String?>>) {
        val lines = mutableListOf<String>()
        lines.add("[引擎] 棋盘状态 (row0=黑方底线):")
        for (i in board.indices) {
            val row = board[i].joinToString(" ") { it ?: "--" }
            lines.add("[引擎]   row$i: $row")
        }
        lines.forEach { AppLog.add(it) }
    }

    fun release() {
        engineClient?.close()
        engineClient = null
        boardRecognizer = null
    }
}
