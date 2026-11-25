package com.example.duallens3dcamera.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.duallens3dcamera.media.MediaStoreUtils
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash log (0.77.1): if the app ever dies with an uncaught exception,
 * write the stack trace to Documents/StereoCapture/crash-<timestamp>.txt
 * (same folder as the debug dumps) before letting the system show the
 * crash dialog. The file can be shared so the developer can see exactly what
 * broke instead of guessing from "it crashed".
 *
 * Installed once from MainActivity.onCreate, so it covers the camera,
 * encoder, and compose threads too — an uncaught exception on ANY thread
 * kills the process, and this is the only record of why.
 */
object CrashLog {
    private const val TAG = "CrashLog"
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(appContext, thread, throwable)
            } catch (_: Exception) {
                // Never let the logger mask the real crash.
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val nowMs = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(Date(nowMs))
        val text = buildString {
            appendLine("Dual-Lens-3D-Camera crash $stamp")
            appendLine("thread: ${thread.name} (id ${thread.id})")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("exception: ${throwable::class.java.name}: ${throwable.message}")
            appendLine("--- stack ---")
            appendLine(Log.getStackTraceString(throwable))
            throwable.cause?.let { appendLine("caused by: ${Log.getStackTraceString(it)}") }
        }
        val displayName = "crash-$stamp.txt"
        // First try the shared Documents/StereoCapture folder (visible in a
        // file manager, same as the debug dumps).
        try {
            val uri = MediaStoreUtils.createPendingJson(context, displayName, nowMs)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }
            MediaStoreUtils.finalizePending(context.contentResolver, uri)
            Log.i(TAG, "Wrote crash log $displayName")
            return
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore crash log failed: ${e.message}")
        }
        // Fall back to the app-private files dir (always writable).
        try {
            val file = File(context.filesDir, displayName)
            FileOutputStream(file).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            Log.i(TAG, "Wrote crash log to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Private crash log failed: ${e.message}")
        }
    }
}
