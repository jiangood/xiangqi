package io.github.jiangood.xq.engine

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class AndroidEngineClient(private val context: Context) {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var isReady = false

    companion object {
        private const val TAG = "AndroidEngineClient"
        private const val ENGINE_NAME = "pikafish-armv8"
        private const val NNUE_NAME = "pikafish.nnue"
        private val ENGINE_NAMES = listOf("pikafish-armv8")
    }

    fun start(): Boolean {
        for (name in ENGINE_NAMES) {
            try {
                val file = findEngine(name) ?: continue
                file.setExecutable(true)
                if (!file.canExecute()) {
                    Runtime.getRuntime().exec(arrayOf("chmod", "700", file.absolutePath))
                        .waitFor()
                }
                if (!file.canExecute()) continue

                process = ProcessBuilder(file.absolutePath)
                    .directory(context.filesDir)
                    .redirectErrorStream(true)
                    .start()
                reader = BufferedReader(InputStreamReader(process!!.inputStream))
                writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))

                if (uciHandshake()) {
                    isReady = true
                    Log.i(TAG, "Engine started: ${file.name}")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start $name", e)
                process?.destroy()
                process = null
            }
        }
        return false
    }

    private fun findEngine(name: String): File? {
        // 1. nativeLibraryDir (extracted by PackageManager from jniLibs)
        val nativeFile = File(context.applicationInfo.nativeLibraryDir, "lib${name}.so")
        if (nativeFile.exists()) {
            Log.i(TAG, "Found engine in nativeLibraryDir: $nativeFile")
            return nativeFile
        }

        // 2. Extract from APK's lib/ directory (fallback)
        try {
            val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
            val apkPath = appInfo.sourceDir
            val abiDir = if (nativeFile.parentFile?.name == "arm64") "arm64-v8a" else "arm64"
            val entryPath = "lib/${abiDir}/lib${name}.so"
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry(entryPath) ?: return null
                val outFile = File(context.filesDir, name)
                if (!outFile.exists()) {
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (outFile.exists()) {
                    Log.i(TAG, "Extracted engine from APK zip: $outFile")
                    return outFile
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "APK zip extraction failed", e)
        }

        return null
    }

    private fun uciHandshake(): Boolean {
        send("uci")
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15)
        var line: String?
        while (System.currentTimeMillis() < deadline) {
            line = reader?.readLine() ?: break
            Log.d(TAG, "engine: $line")
            if (line.startsWith("uciok")) {
                val nnueFile = File(context.filesDir, NNUE_NAME)
                if (nnueFile.exists()) {
                    send("setoption name EvalFile value ${nnueFile.absolutePath}")
                }
                send("isready")
                val readyDeadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10)
                while (System.currentTimeMillis() < readyDeadline) {
                    line = reader?.readLine() ?: break
                    Log.d(TAG, "engine: $line")
                    if (line.startsWith("readyok")) return true
                }
                return false
            }
        }
        return false
    }

    fun getTopMoves(fen: String, multiPv: Int = 3, depth: Int = 10): List<String> {
        if (!isReady) return emptyList()
        return try {
            send("position fen $fen")
            send("setoption name MultiPV value $multiPv")
            send("go depth $depth")
            val moves = mutableListOf<String>()
            var line: String?
            val deadline = System.currentTimeMillis() + 30000
            while (System.currentTimeMillis() < deadline) {
                line = reader?.readLine() ?: break
                if (line.startsWith("bestmove")) {
                    val best = line.split(" ").getOrNull(1)
                    if (best != null && best !in moves) moves.add(best)
                    break
                }
                if (line.contains(" pv ")) {
                    val parts = line.split(" pv ")
                    if (parts.size > 1) {
                        val move = parts[1].trim().split(" ").first()
                        if (move !in moves) moves.add(move)
                    }
                }
            }
            moves
        } catch (e: Exception) {
            Log.e(TAG, "getTopMoves failed", e)
            emptyList()
        }
    }

    private fun send(cmd: String) {
        try {
            writer?.write("$cmd\n")
            writer?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "send failed", e)
        }
    }

    fun close() {
        isReady = false
        try {
            send("quit")
            process?.waitFor(2, TimeUnit.SECONDS)
        } catch (_: Exception) {}
        process?.destroyForcibly()
        try { reader?.close() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
    }
}
