package com.pixeltek.windhover.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Logcat plus a rolling on-device log file (1 MB, one rotation), so diagnostics can be exported
 * from a release build without adb.
 */
object DiagLog {
    private const val MAX_BYTES = 1_000_000L
    private var file: File? = null
    private var rotated: File? = null
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "diaglog").apply { isDaemon = true } }
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

    fun init(context: Context) {
        file = File(context.filesDir, "diag.log")
        rotated = File(context.filesDir, "diag.1.log")
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        write("D", tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        write("I", tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        Log.w(tag, msg, t)
        write("W", tag, if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}" else msg)
    }

    fun files(): List<File> = listOfNotNull(rotated, file).filter { it.exists() }

    private fun write(level: String, tag: String, msg: String) {
        val f = file ?: return
        val line = "${LocalDateTime.now().format(formatter)} $level/$tag: $msg\n"
        executor.execute {
            try {
                if (f.length() > MAX_BYTES) {
                    rotated?.delete()
                    f.renameTo(rotated ?: return@execute)
                }
                f.appendText(line)
            } catch (_: IOException) {
            }
        }
    }
}
