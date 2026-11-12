package com.sideaccountapps.ads.core.internal

/**
 * SDK init'i bitene kadar yükleme işlerini bekleten kapı. Android'e bağımlı değildir, yalnızca main thread'den
 * kullanılır.
 *
 * - [runWhenReady]: kapı açıksa iş hemen, değilse kapı açılınca bir kez çalışır.
 * - [start]: init başladı; [timeoutMs] içinde [open] gelmezse kapı uyarıyla açılır, sonradan gelen [open] etkisizdir.
 * - Kapı kapalıyken init başlamamış olabilir (ör. consent bekleniyor); zaman aşımı yalnızca [start] ile kurulur.
 */
internal class InitGate(private val timer: Timer, private val timeoutMs: Long = 20_000) {

    var isOpen = false
        private set

    private var started = false
    private val pending = ArrayList<() -> Unit>()
    private val readyCallbacks = ArrayList<Runnable>()

    fun runWhenReady(job: () -> Unit) {
        if (isOpen) job() else pending += job
    }

    /** Kapı açılınca (init bitince ya da zaman aşımında) bir kez çağrılır. */
    fun onReady(callback: Runnable) {
        if (isOpen) callback.run() else readyCallbacks += callback
    }

    /** Init başladı. [onTimeout] kapı süre dolduğu için açılmadan hemen önce çağrılır. */
    fun start(onTimeout: () -> Unit) {
        if (started) return
        started = true
        timer.after(timeoutMs) {
            if (!isOpen) {
                onTimeout()
                open()
            }
        }
    }

    /** Kapıyı açar ve bekleyen işleri çalıştırır. Dönüş: çalıştırılan iş sayısı (zaten açıksa 0). */
    fun open(): Int {
        if (isOpen) return 0
        isOpen = true
        // Önce kuyruktan çıkar: çalışan iş yeni iş ekleyebilir, hiçbir iş iki kez çalışmaz.
        val jobs = pending.toList()
        pending.clear()
        jobs.forEach { it() }
        val callbacks = readyCallbacks.toList()
        readyCallbacks.clear()
        callbacks.forEach { it.run() }
        return jobs.size
    }
}
