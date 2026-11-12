package com.sideaccountapps.ads.core.internal

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sideaccountapps.ads.core.LogLevel
import com.sideaccountapps.ads.core.LogListener

/** Kütüphanenin logger'ı. DEBUG/INFO yalnızca debug açıkken logcat'e yazılır; listener hepsini alır. */
internal object Logger {
    private const val TAG = "SideAds"

    @Volatile var debugEnabled = false
    @Volatile var listener: LogListener? = null

    fun d(message: String) {
        if (debugEnabled) Log.d(TAG, message)
        listener?.onLog(LogLevel.DEBUG, message)
    }

    fun i(message: String) {
        if (debugEnabled) Log.i(TAG, message)
        listener?.onLog(LogLevel.INFO, message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
        listener?.onLog(LogLevel.WARN, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        listener?.onLog(LogLevel.ERROR, message)
    }
}

/** Gecikmeli iş zamanlayıcı; dönen fonksiyon işi iptal eder. Testlerde elle ilerleyen sahtesi kullanılır. */
internal fun interface Timer {
    fun after(delayMs: Long, task: () -> Unit): () -> Unit
}

/** Main thread yardımcıları. Kütüphanenin tüm durumu main thread'de değişir. */
internal object Main {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /** Main thread'deyse hemen, değilse main thread'e post ederek çalıştırır. */
    fun run(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    /** Her zaman sıraya koyar (çağıranın dönmesini bekler). */
    fun post(block: () -> Unit) {
        handler.post(block)
    }

    val timer = Timer { delayMs, task ->
        val runnable = Runnable { task() }
        handler.postDelayed(runnable, delayMs)
        val cancel: () -> Unit = { handler.removeCallbacks(runnable) }
        cancel
    }
}
