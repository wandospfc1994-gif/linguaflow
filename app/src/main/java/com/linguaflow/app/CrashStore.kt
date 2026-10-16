package com.linguaflow.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

object CrashStore {
    private const val FILE_NAME = "crash.txt"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun write(context: Context, thread: Thread, error: Throwable) {
        runCatching {
            val text = buildString {
                appendLine("App: ${context.packageName}")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Thread: ${thread.name}")
                appendLine()
                append(Log.getStackTraceString(error))
            }
            file(context).writeText(text)
        }
    }

    fun read(context: Context): String? =
        runCatching {
            val f = file(context)
            if (f.exists()) f.readText() else null
        }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
