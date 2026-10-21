package com.xreal.stereoview.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Process
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lets crashes be reported without adb: Java crashes are written to a file by an uncaught
 * exception handler, native crashes and ANRs are recovered from the system's exit records,
 * and the next launch offers the report for sharing.
 */
object CrashReporter {
    private const val REPORT_FILE = "crash_report.txt"
    private const val PREFS = "crash_reporter"
    private const val KEY_LAST_EXIT = "last_exit_timestamp"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val report = buildString {
                    appendLine("Crash in thread '${thread.name}' at ${now()}")
                    appendLine(deviceInfo(app))
                    appendLine()
                    appendLine(error.stackTraceToString())
                    appendLine("--- logcat ---")
                    append(ownLogcat(pidOnly = true))
                }
                File(app.filesDir, REPORT_FILE).writeText(report)
            } catch (_: Throwable) {
                // Never let reporting hide the original crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Shows the report from the previous run, if it crashed. Call from the launcher activity. */
    fun showPendingReport(context: Context) {
        val report = takePendingReport(context) ?: return
        showReport(context, "The app crashed last time", report)
    }

    /** Shows [text] with Share and Copy buttons. */
    fun showReport(context: Context, title: String, text: String) {
        val view = TextView(context).apply {
            this.text = text
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setPadding(32, 16, 32, 16)
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(ScrollView(context).apply { addView(view) })
            .setPositiveButton("Share") { _, _ -> share(context, text) }
            .setNeutralButton("Copy") { _, _ ->
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Stereo Passthrough log", text))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** This app's recent log lines (apps may read their own logs without extra permissions). */
    fun ownLogcat(pidOnly: Boolean = false, lines: Int = 400): String = try {
        val cmd = mutableListOf("logcat", "-d", "-v", "threadtime", "-t", lines.toString())
        if (pidOnly) cmd += listOf("--pid", Process.myPid().toString())
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText()
    } catch (e: Exception) {
        "(logcat unavailable: ${e.message})"
    }

    fun deviceInfo(context: Context): String {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        return "App $version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.DISPLAY}"
    }

    private fun takePendingReport(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_EXIT, 0)
        val exit = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 1)
            .firstOrNull()
            ?.takeIf { it.timestamp > lastSeen }
        exit?.let { prefs.edit().putLong(KEY_LAST_EXIT, it.timestamp).apply() }

        val file = File(context.filesDir, REPORT_FILE)
        if (file.exists()) {
            val report = file.readText()
            file.delete()
            return report
        }

        val reason = exit?.reason ?: return null
        if (reason != ApplicationExitInfo.REASON_CRASH_NATIVE && reason != ApplicationExitInfo.REASON_ANR &&
            reason != ApplicationExitInfo.REASON_CRASH
        ) return null
        return buildString {
            val kind = when (reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                ApplicationExitInfo.REASON_ANR -> "App not responding"
                else -> "Crash"
            }
            appendLine("$kind at ${now(exit.timestamp)}: ${exit.description ?: "no description"}")
            appendLine(deviceInfo(context))
            exit.traceInputStream?.use {
                appendLine()
                appendLine("--- trace ---")
                // Native traces are protobuf tombstones; their readable strings hold the backtrace.
                appendLine(if (reason == ApplicationExitInfo.REASON_ANR) it.bufferedReader().readText() else printableStrings(it))
            }
            appendLine("--- logcat ---")
            append(ownLogcat())
        }
    }

    private fun printableStrings(input: InputStream, maxChars: Int = 60_000): String {
        val out = StringBuilder()
        val run = StringBuilder()
        for (b in input.readBytes()) {
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e) {
                run.append(c.toChar())
            } else {
                if (run.length >= 4) out.appendLine(run)
                run.clear()
            }
            if (out.length > maxChars) break
        }
        if (run.length >= 4) out.appendLine(run)
        return out.toString()
    }

    private fun share(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Stereo Passthrough log")
            putExtra(Intent.EXTRA_TEXT, text.takeLast(200_000))
        }
        context.startActivity(Intent.createChooser(intent, "Share log"))
    }

    private fun now(time: Long = System.currentTimeMillis()) =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))
}
