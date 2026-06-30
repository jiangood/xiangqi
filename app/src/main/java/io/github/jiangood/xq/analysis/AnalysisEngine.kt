package io.github.jiangood.xq.analysis

import android.content.Context
import android.graphics.Bitmap
import io.github.jiangood.xq.engine.AndroidEngineClient
import io.github.jiangood.xq.opencv.CalibrationData
import io.github.jiangood.xq.opencv.TemplatePieceRecognizer
import io.github.jiangood.xq.platform.AndroidImageUtils
import io.github.jiangood.xq.settings.CalibrationManager
import io.github.jiangood.xq.util.AppLog
import io.github.jiangood.xq.util.FenUtil
import io.github.jiangood.xq.util.NotationConverter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.*
import java.io.File
import java.io.FileOutputStream

data class AnalysisResult(
    val board: Array<Array<String?>>,
    val fen: String,
    val standardMoves: List<String>,
    val chineseMoves: List<String>,
    val visualizationPath: String? = null
)

object AnalysisEngine {
    var engineClient: AndroidEngineClient? = null
        private set
    var boardRecognizer: TemplatePieceRecognizer? = null
        private set
    var calibrationData: CalibrationData? = null
        private set
    var isNnueMissing = false
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
                            AndroidImageUtils.copyToFile(input, nnueFile)
                        }
                        AppLog.add("[引擎] NNUE 解压完成")
                    } catch (e: java.io.FileNotFoundException) {
                        isNnueMissing = true
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

                AppLog.add("[引擎] 加载校准数据...")
                val calibData = CalibrationManager.load(context)
                calibrationData = calibData
                if (calibData != null) {
                    val templateDir = CalibrationManager.getTemplateFileDir(context)
                    boardRecognizer = TemplatePieceRecognizer(calibData, templateDir)
                    AppLog.add("[引擎] 校准数据加载成功，使用模板匹配")
                } else {
                    AppLog.add("[引擎] 未找到校准数据，请先在设置中完成棋盘棋子校准")
                }

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

    suspend fun recognize(context: Context, imagePath: String): Array<Array<String?>>? = withContext(Dispatchers.IO) {
    try {
        val calibData = CalibrationManager.load(context) ?: return@withContext null
        val templateDir = CalibrationManager.getTemplateFileDir(context)
        val recognizer = TemplatePieceRecognizer(calibData, templateDir)
        recognizer.parseBoard(imagePath)
    } catch (e: Exception) {
        AppLog.add("[引擎] 识别失败: ${e.message}")
        null
    }
}

    suspend fun analyze(imageFile: File): AnalysisResult? {
        return withContext(Dispatchers.IO) {
            try {
                initComplete.await()
                val recognizer = boardRecognizer
                val engine = engineClient
                if (recognizer == null || engine == null) {
                    AppLog.add("[引擎] 分析失败: recognizer=${recognizer != null}, engine=${engine != null}")
                    if (recognizer == null) {
                        AppLog.add("[引擎] 请先在设置中完成棋盘棋子校准")
                    }
                    return@withContext null
                }
                AppLog.add("[引擎] 开始模板匹配棋盘识别: ${imageFile.name}")
                val rawBoard = recognizer.parseBoard(imageFile.absolutePath)
                AppLog.add("[引擎] 棋盘识别完成, 检测到棋子: ${FenUtil.countPieces(rawBoard)}")
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
                val vizPath = generateVisualization(imageFile.absolutePath, board, moves.firstOrNull())
                AnalysisResult(board, fen, moves, chineseMoves, vizPath)
            } catch (e: Exception) {
                AppLog.add("[引擎] 分析异常: ${e.message}")
                null
            }
        }
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

    private val PIECE_CHINESE = FenUtil.PIECE_CHINESE

    private fun generateVisualization(
        imagePath: String,
        board: Array<Array<String?>>,
        bestMove: String?
    ): String? {
        val calib = calibrationData ?: return null
        val bmp = AndroidImageUtils.renderBoardVisualization(imagePath, calib.grid, board, bestMove) ?: return null
        val outDir = File(imagePath).parentFile
        val outPath = File(outDir, "visualization.jpg").absolutePath
        FileOutputStream(outPath).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        bmp.recycle()
        return outPath
    }

    fun release() {
        engineClient?.close()
        engineClient = null
        boardRecognizer = null
    }
}
