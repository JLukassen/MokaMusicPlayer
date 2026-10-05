package com.mokamusic.player

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MokaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogStore.install(this)
    }
}

object CrashLogStore {
    private const val CRASH_FILE = "last_crash.txt"
    private const val ERROR_FILE = "last_error.txt"
    private const val TAG = "MokaCrash"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(context, CRASH_FILE, "UNCAUGHT on ${thread.name}", throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun nonFatal(context: Context, where: String, throwable: Throwable) {
        Log.e(TAG, where, throwable)
        runCatching { write(context, ERROR_FILE, "NON-FATAL: $where", throwable) }
    }

    fun lastCrash(context: Context): String? = runCatching {
        context.filesDir.resolve(CRASH_FILE).takeIf { it.exists() }?.readText()
    }.getOrNull()

    fun lastError(context: Context): String? = runCatching {
        context.filesDir.resolve(ERROR_FILE).takeIf { it.exists() }?.readText()
    }.getOrNull()

    private fun write(context: Context, fileName: String, prefix: String, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        context.filesDir.resolve(fileName).writeText("$stamp\n$prefix\n${sw}\n")
    }
}
