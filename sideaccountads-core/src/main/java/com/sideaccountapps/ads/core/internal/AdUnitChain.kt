package com.sideaccountapps.ads.core.internal

/** Bir isteğe hiç cevap gelmezse adımın hata sayılacağı süre. Normal akışta devreye girmez. */
internal const val NO_RESPONSE_MS = 90_000L

internal sealed class ChainResult<out T> {
    class Loaded<out T>(val adUnitId: String, val value: T) : ChainResult<T>()
    class Failed(val error: String) : ChainResult<Nothing>()
}

/**
 * Bir placement'ın ad unit'lerini sırayla dener. Zaman aşımı yoktur: SDK "yüklendi" derse durulur, hata verirse
 * sıradakine geçilir; hepsi hata verirse tek bir [ChainResult.Failed] döner (her ad unit'in hatası mesajda).
 * Tek emniyet: [noResponseMs] içinde cevap gelmeyen adım hata sayılır, sonradan gelen sonucu atılır
 * (placement oturum boyunca "yükleniyor" kalmasın diye).
 *
 * Android'e bağımlı değildir; main thread'den kullanılır.
 */
internal class AdUnitChain(private val timer: Timer, private val noResponseMs: Long = NO_RESPONSE_MS) {

    /** Adımın yükleyicisi: yüklemeyi başlatır ve sonucu [Step]'e bildirir. */
    fun interface Loader<T : Any> {
        fun load(step: Step<T>)
    }

    /** Zincirin bir adımı. Sonuç yalnızca bir kez kabul edilir. */
    class Step<T : Any> internal constructor(
        val adUnitId: String,
        /** Loglar için "2/3" gibi. */
        val position: String,
        private val discard: (T) -> Unit,
        private val onSuccess: (T) -> Unit,
        private val onFailure: (String) -> Unit
    ) {
        private var settled = false
        private var succeeded = false
        private var cleanup: (() -> Unit)? = null
        internal var cancelTimer: () -> Unit = {}

        internal val isSettled: Boolean get() = settled

        fun succeed(value: T) {
            if (settled) {
                // Hata ya da cevapsızlık yüzünden terk edilmiş adımın geç sonucu.
                Logger.d("Late load result for $adUnitId discarded")
                discard(value)
                return
            }
            settled = true
            succeeded = true
            cancelTimer()
            onSuccess(value)
        }

        fun fail(code: Int, message: String) {
            if (!settled) abandon("[$code] $message")
        }

        /** Adım hata ya da cevapsızlıkla terk edilince çalışacak temizlik (ör. banner view'ını destroy etmek). */
        fun onAbandon(cleanup: () -> Unit) {
            when {
                !settled -> this.cleanup = cleanup
                !succeeded -> cleanup() // senkron hata, temizlik kaydedilmeden geldi
            }
        }

        internal fun abandon(reason: String) {
            settled = true
            cancelTimer()
            cleanup?.invoke()
            cleanup = null
            onFailure(reason)
        }
    }

    /**
     * @param label loglar için ör. "INTERSTITIAL/inter_default".
     * @param discard terk edilmiş adımdan geç gelen başarılı sonucu bırakır.
     * @param isActive her adımdan önce sorulur; false ise zincir sessizce durur (ör. banner destroy edildi).
     * @param done sonuç tam bir kez (ya da [isActive] false olduysa hiç) çağrılır.
     */
    fun <T : Any> run(
        label: String,
        adUnitIds: List<String>,
        loader: Loader<T>,
        discard: (T) -> Unit,
        isActive: () -> Boolean = { true },
        done: (ChainResult<T>) -> Unit
    ) {
        val errors = ArrayList<String>()

        fun attempt(index: Int) {
            if (!isActive()) return
            if (index >= adUnitIds.size) {
                done(ChainResult.Failed(errors.joinToString(" | ").ifEmpty { "no ad unit configured for $label" }))
                return
            }
            val adUnitId = adUnitIds[index]
            val step = Step<T>(
                adUnitId = adUnitId,
                position = "${index + 1}/${adUnitIds.size}",
                discard = discard,
                onSuccess = { value -> done(ChainResult.Loaded(adUnitId, value)) },
                onFailure = { reason ->
                    Logger.d("$label: ad unit $adUnitId failed (step ${index + 1}/${adUnitIds.size}): $reason")
                    errors += "$adUnitId: $reason"
                    attempt(index + 1)
                }
            )
            step.cancelTimer = timer.after(noResponseMs) {
                if (!step.isSettled) {
                    Logger.w("$label: no response from $adUnitId within ${noResponseMs / 1000}s; treating it as failed")
                    step.abandon("no response within ${noResponseMs / 1000}s")
                }
            }
            loader.load(step)
        }

        attempt(0)
    }
}
