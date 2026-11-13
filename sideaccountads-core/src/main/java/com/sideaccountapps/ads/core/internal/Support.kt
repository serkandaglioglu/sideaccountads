package com.sideaccountapps.ads.core.internal

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.sideaccountapps.ads.core.RevenueEvent
import com.sideaccountapps.ads.core.RevenueListener
import com.sideaccountapps.ads.core.SideAdsListener
import com.sideaccountapps.ads.core.mediation.AdMediator

/** Listener'ları tutar; tüm olayları main thread'de iletir. */
internal object Dispatch {
    @Volatile var listener: SideAdsListener? = null
    @Volatile var revenueListener: RevenueListener? = null

    fun event(block: (SideAdsListener) -> Unit) {
        val l = listener ?: return
        Main.run { block(l) }
    }

    fun revenue(event: RevenueEvent) {
        val l = revenueListener ?: return
        Main.run { l.onRevenue(event) }
    }
}

/** Uygulamaya eklenen mediator modülünü manifest meta-data'sından bulur. */
internal object MediatorLocator {

    const val META_DATA_KEY = "com.sideaccountapps.ads.MEDIATOR"

    fun find(context: Context): AdMediator? {
        val className = try {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData?.getString(META_DATA_KEY)
        } catch (e: Exception) {
            Logger.w("Manifest meta-data could not be read: ${e.message}")
            null
        }
        if (className.isNullOrBlank()) {
            Logger.e("No mediator module found; add a mediator module such as sideaccountads-admob to the app dependencies")
            return null
        }
        return try {
            Class.forName(className).getDeclaredConstructor().newInstance() as AdMediator
        } catch (t: Throwable) {
            Logger.e("Mediator $className could not be created", t)
            null
        }
    }
}

/** Google UMP ile consent akışı. MAX gibi diğer mediator'lar da UMP'nin yazdığı TCF bilgisini okur. */
internal object UmpConsent {

    private fun info(context: Context): ConsentInformation = UserMessagingPlatform.getConsentInformation(context)

    fun canRequestAds(context: Context): Boolean = try {
        info(context).canRequestAds()
    } catch (_: Exception) {
        false
    }

    /**
     * Consent bilgisini günceller, gerekiyorsa formu gösterir; sonunda (hata olsa da) [onComplete] çağrılır.
     * `requestConsentInfoUpdate` senkron çağrılır: dönüşte [canRequestAds] önceki oturumun consent'ini yansıtır.
     */
    fun gather(activity: Activity, testMode: Boolean, testDeviceHashedIds: List<String>, onComplete: (Boolean) -> Unit) {
        val params = ConsentRequestParameters.Builder()
        if (testMode && testDeviceHashedIds.isNotEmpty()) {
            params.setConsentDebugSettings(
                ConsentDebugSettings.Builder(activity)
                    .apply { testDeviceHashedIds.forEach { addTestDeviceHashedId(it) } }
                    .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                    .build()
            )
        } else if (testMode) {
            Logger.w(
                "testMode=true but admobTestDeviceIds is empty: UMP debug geography (EEA) is NOT applied. Outside " +
                    "the EEA the consent form never appears. Copy the id from the UMP log line " +
                    "addTestDeviceHashedId(\"...\")"
            )
        }
        val consentInfo = info(activity)
        consentInfo.requestConsentInfoUpdate(
            activity,
            params.build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    formError?.let { Logger.w("UMP consent form error: ${it.errorCode} ${it.message}") }
                    onComplete(consentInfo.canRequestAds())
                }
            },
            { requestError ->
                Logger.w("UMP consent info update failed: ${requestError.errorCode} ${requestError.message}")
                // Önceki oturumdan geçerli consent olabilir.
                onComplete(consentInfo.canRequestAds())
            }
        )
    }

    fun requiresPrivacyOptions(context: Context): Boolean = try {
        info(context).privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    } catch (_: Exception) {
        false
    }

    fun showPrivacyOptions(activity: Activity, onDismissed: Runnable?) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            formError?.let { Logger.w("UMP privacy options form error: ${it.errorCode} ${it.message}") }
            onDismissed?.run()
        }
    }

    fun reset(context: Context) {
        try {
            info(context).reset()
        } catch (e: Exception) {
            Logger.w("UMP reset failed: ${e.message}")
        }
    }
}
