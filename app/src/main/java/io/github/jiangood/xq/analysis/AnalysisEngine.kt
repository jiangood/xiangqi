package io.github.jiangood.xq.analysis

import android.content.Context
import android.graphics.Bitmap
import io.github.jiangood.xq.engine.AndroidEngineClient
import io.github.jiangood.xq.opencv.TemplatePieceRecognizer
import io.github.jiangood.xq.platform.AndroidImageUtils
import io.github.jiangood.xq.data.AnalysisRecord
import io.github.jiangood.xq.data.AppDatabase
import io.github.jiangood.xq.util.AppLog
import io.github.jiangood.xq.util.FenUtil
import io.github.jiangood.xq.util.NotationConverter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.FileOutputStream

data class AnalysisResult(
    val board: Array<Array<String?>>,
    val fen: String,
    val standardMoves: List<String>,
    val chineseMoves: List<String>,
    val visualizationPath: String? = null,
    val sourceImagePath: String? = null,
    val logs: String = ""
)

object AnalysisEngine {
    var engineClient: AndroidEngineClient? = null
        private set
    var boardRecognizer: TemplatePieceRecognizer? = null
        private set
    var isNnueMissing = false
        private set

    private var appContext: Context? = null

    private val initComplete = CompletableDeferred<Unit>()

    fun init(context: Context) {
        if (initComplete.isCompleted) return
        appContext = context
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nnueFile = File(context.filesDir, "pikafish.nnue")
                if (!nnueFile.exists()) {
                    try {
                        AppLog.add("[引擎] 解压 NNUE 权重文件...")
                        context.assets.open("pikafish.nnue").use { input ->
                            AndroidImageUtils.copyToFile(input, nnueFile)
                        }
                    } catch (e: java.io.FileNotFoundException) {
                        isNnueMissing = true
                        AppLog.add("[引擎] NNUE 权重文件不存在（thin 包），请先安装完整版")
                    } catch (e: Exception) {
                        AppLog.add("[引擎] NNUE 解压跳过: ${e.message}")
                    }
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
                } else {
                    AppLog.add("[引擎] 引擎启动失败")
                }

                AppLog.add("[引擎] 加载内置棋子模板...")
                val templateFiles = context.assets.list("templates")
                if (templateFiles == null || templateFiles.isEmpty()) {
                    AppLog.add("[引擎] 未找到模板文件")
                    initComplete.complete(Unit)
                    return@launch
                }
                val mats = mutableListOf<Mat>()
                val types = mutableListOf<String>()
                for (fname in templateFiles) {
                    if (!fname.endsWith(".png")) continue
                    val pieceType = fname.replace(".png", "")
                    val inputStream = context.assets.open("templates/$fname")
                    val bytes = inputStream.readBytes()
                    inputStream.close()
                    val mat = Imgcodecs.imdecode(org.opencv.core.MatOfByte(*bytes), Imgcodecs.IMREAD_GRAYSCALE)
                    if (!mat.empty()) {
                        mats.add(mat)
                        types.add(pieceType)
                    }
                }
                if (mats.isEmpty()) {
                    AppLog.add("[引擎] 未加载到有效模板")
                } else {
                    boardRecognizer = TemplatePieceRecognizer(
                        mats.toTypedArray(),
                        types.toTypedArray()
                    )
                    AppLog.add("[引擎] 成功加载 ${mats.size} 个内置棋子模板")
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

    suspend fun analyze(imageFile: File): AnalysisResult? {
        return withContext(Dispatchers.IO) {
            AppLog.startSession()
            try {
                initComplete.await()
                val recognizer = boardRecognizer
                val engine = engineClient
                var result: AnalysisResult? = null
                var totalElapsed = 0L

                if (recognizer == null || engine == null) {
                    AppLog.add("[引擎] 分析失败: 识别器或引擎未初始化")
                } else {
                    AppLog.add("[引擎] === 开始分析: ${imageFile.name} ===")

                    // Stage 1: Board recognition
                    AppLog.add("[引擎] 阶段1/3: 模板匹配棋盘识别...")
                    val recogStart = System.currentTimeMillis()
                    val board = recognizer.parseBoard(imageFile.absolutePath)
                    val recogElapsed = System.currentTimeMillis() - recogStart
                    val totalPieces = FenUtil.countPieces(board)

                    AppLog.add("[引擎] 阶段1完成: 耗时 ${recogElapsed}ms, 检测到 $totalPieces 个棋子")

                    // Debug: output each row
                    for (r in 0 until 10) {
                        val row = board[r]
                        val count = row.count { it != null }
                        if (count > 0) {
                            AppLog.add("[引擎]   row$r: ${row.joinToString(",") { it ?: "__" }}")
                        }
                    }

                    if (totalPieces == 0) {
                        AppLog.add("[引擎]   未检测到任何棋子!")
                    }

                    // Stage 2: FEN generation
                    AppLog.add("[引擎] 阶段2/3: 生成 FEN...")
                    val fenStart = System.currentTimeMillis()
                    val fen = FenUtil.toFen(board)
                    val fenElapsed = System.currentTimeMillis() - fenStart
                    AppLog.add("[引擎] 阶段2完成: 耗时 ${fenElapsed}ms, FEN=$fen")

                    // Stage 3: Engine analysis
                    AppLog.add("[引擎] 阶段3/3: 引擎分析中 (depth=${io.github.jiangood.xq.settings.SettingsManager.getDepth()})...")
                    val engineStart = System.currentTimeMillis()
                    val moves = engine.getBestMove(fen, io.github.jiangood.xq.settings.SettingsManager.getDepth())
                    val engineElapsed = System.currentTimeMillis() - engineStart
                    AppLog.add("[引擎] 阶段3完成: 耗时 ${engineElapsed}ms, 返回 ${moves.size} 条走法")

                    totalElapsed = recogElapsed + fenElapsed + engineElapsed
                    AppLog.add("[引擎] 总耗时: ${totalElapsed}ms")
                    val chineseMoves = moves.map { move ->
                        NotationConverter.convertToChineseNotation(board, move)
                    }
                    if (chineseMoves.isNotEmpty()) {
                        AppLog.add("[引擎] 推荐走法: ${chineseMoves[0]}")
                    }

                    val grid = recognizer.getLastGrid()
                    val vizPath = if (grid != null) {
                        AppLog.add("[引擎] 生成可视化标注图...")
                        val vizStart = System.currentTimeMillis()
                        generateVisualization(imageFile.absolutePath, grid, board, moves.firstOrNull()).also {
                            AppLog.add("[引擎] 标注图生成完成: ${System.currentTimeMillis() - vizStart}ms -> ${it ?: "失败"}")
                        }
                    } else null

                    AppLog.add("[引擎] === 分析完成 ===")
                    result = AnalysisResult(board, fen, moves, chineseMoves, vizPath, sourceImagePath = imageFile.absolutePath)
                }

                val sessionLogs = AppLog.endSession()

                if (result != null) {
                    persistResult(
                        status = "success",
                        move = result.chineseMoves.firstOrNull(),
                        fen = result.fen,
                        pieceCount = FenUtil.countPieces(result.board),
                        elapsedMs = totalElapsed,
                        errorMessage = null,
                        screenshotPath = result.sourceImagePath,
                        visualizationPath = result.visualizationPath,
                        logs = sessionLogs
                    )
                }

                result?.copy(logs = sessionLogs)
            } catch (e: Exception) {
                AppLog.add("[引擎] 分析异常: ${e.message}")
                val sessionLogs = AppLog.endSession()
                persistResult(
                    status = "error",
                    move = null,
                    fen = null,
                    pieceCount = null,
                    elapsedMs = null,
                    errorMessage = e.message,
                    screenshotPath = imageFile.absolutePath,
                    visualizationPath = null,
                    logs = sessionLogs
                )
                null
            }
        }
    }

    private fun persistResult(
        status: String,
        move: String?,
        fen: String?,
        pieceCount: Int?,
        elapsedMs: Long?,
        errorMessage: String?,
        screenshotPath: String?,
        visualizationPath: String?,
        logs: String
    ) {
        val ctx = appContext ?: return
        val record = AnalysisRecord(
            timestamp = System.currentTimeMillis(),
            status = status,
            move = move,
            fen = fen,
            pieceCount = pieceCount,
            elapsedMs = elapsedMs,
            errorMessage = errorMessage,
            screenshotPath = screenshotPath,
            visualizationPath = visualizationPath,
            logs = logs
        )
        val db = AppDatabase.getInstance(ctx)
        db.analysisRecordDao().insert(record)
        val count = db.analysisRecordDao().count()
        if (count > 200) {
            db.analysisRecordDao().deleteOldest(count - 200)
            cleanupOldScreenshots(ctx)
        }
    }

    private fun cleanupOldScreenshots(ctx: Context) {
        val dir = File(ctx.filesDir, "xiangqi_screenshots")
        if (!dir.exists()) return
        val db = AppDatabase.getInstance(ctx)
        val kept = db.analysisRecordDao().getAllScreenshotPaths().mapNotNull { it }.toSet() +
                   db.analysisRecordDao().getAllVisualizationPaths().mapNotNull { it }.toSet()
        dir.listFiles()?.forEach { file ->
            if (file.absolutePath !in kept) {
                file.delete()
            }
        }
    }

    private fun generateVisualization(
        imagePath: String,
        grid: Array<Array<Point>>,
        board: Array<Array<String?>>,
        bestMove: String?
    ): String? {
        val bmp = AndroidImageUtils.renderBoardVisualization(imagePath, grid, board, bestMove) ?: return null
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
