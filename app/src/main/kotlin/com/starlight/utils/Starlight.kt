package com.starlight.utils

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.crashlytics
import com.starlight.BuildConfig
import com.starlight.preferences.PreferenceKeys.fromPreferences
import com.starlight.utils.Constants.FINGERPRINT_PREF
import com.starlight.utils.Constants.GLES_PREF_STRING
import com.starlight.utils.Constants.RENDERER_PREF
import com.starlight.utils.Constants.STARLIGHT_PREF_NAME
import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.severe
import com.starlight.utils.LimeLog.warning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.security.GeneralSecurityException


class Starlight : Application() {

    init {
        if (BuildConfig.DEBUG) {
            try {
                Class.forName("dalvik.system.CloseGuard")
                    .getMethod("setEnabled", Boolean::class.javaPrimitiveType)
                    .invoke(null, true)
            } catch (e: ReflectiveOperationException) {
                warning("Starlight: Failed to enable CloseGuard$e")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize Tink AEAD
        try {
            aead = TinkAead(this).get()
        } catch (e: GeneralSecurityException) {
            severe("Failed to initialize Tink AEAD: ${e.message}")
        } catch (e: IOException) {
            severe("Failed to initialize Tink AEAD: ${e.message}")
        }

        instance = this

        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


        val config = fromPreferences(this@Starlight)

        val isStatsEnabled = config.statsEnabled
        val isGlRenderer = config.renderer

        LimeLog.initialize(this@Starlight)

        if (isStatsEnabled) {
            initializeFirebase()
        } else {
            deactivateFirebase()
        }

        if (isGlRenderer.isEmpty()) {

            val gpuInfo = fetchDeviceGpuInfo()

            val activityManager =
                getSystemService(ACTIVITY_SERVICE) as ActivityManager

            val configurationInfo = activityManager.deviceConfigurationInfo
            val glesVersion = configurationInfo.reqGlEsVersion

            applicationScope.launch{
                dataStore.edit { preferences ->
                    preferences[stringPreferencesKey(RENDERER_PREF)] = gpuInfo.renderer
                    preferences[stringPreferencesKey(FINGERPRINT_PREF)] = Build.FINGERPRINT
                    preferences[intPreferencesKey(GLES_PREF_STRING)] = glesVersion
                }
            }

            info("GPU_INFO: Renderer: ${gpuInfo.renderer}")
        }
    }

    private fun initializeFirebase() {
        Firebase.crashlytics.isCrashlyticsCollectionEnabled = true
        FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(true)
    }

    private fun deactivateFirebase() {
        Firebase.crashlytics.isCrashlyticsCollectionEnabled = false
        FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(false)
    }

    companion object {

        var aead: Aead? = null

        lateinit var instance: Starlight
            private set

        lateinit var applicationScope: CoroutineScope
            private set

        private const val DATASTORE_NAME = STARLIGHT_PREF_NAME

        val Context.dataStore by preferencesDataStore(name = DATASTORE_NAME)

        // Job and scope for quitting the game
        var quitGameJob: Job? = null

        // Helper function to get the device name
        fun getDeviceName(): String {
            val manufacturer = Build.MANUFACTURER
            val model = Build.MODEL
            return if (model.startsWith(manufacturer)) {
                capitalize(model)
            } else {
                capitalize(manufacturer) + " " + model
            }
        }

        // Helper function to capitalize a string
        private fun capitalize(s: String?): String {
            if (s.isNullOrEmpty()) {
                return ""
            }
            val first = s[0]
            return if (Character.isUpperCase(first)) {
                s
            } else {
                first.uppercaseChar().toString() + s.substring(1)
            }
        }
    }
}