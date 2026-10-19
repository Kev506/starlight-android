package com.starlight.ui.helpers

import android.annotation.SuppressLint
import android.app.GameManager
import android.app.GameState
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.starlight.models.MainViewModel
import com.starlight.preferences.PreferenceKeys
import com.starlight.utils.Constants
import com.starlight.utils.LimeLog
import com.starlight.utils.Starlight.Companion.applicationScope
import com.starlight.utils.Starlight.Companion.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

// Helper object containing UI utility functions
object UiHelper {

    // Helper function to set the game mode status
    private fun setGameModeStatus(
        context: Context,
        isStreaming: Boolean,
        isInterruptible: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val gameManager = context.getSystemService(GameManager::class.java)

            if (gameManager == null) {
                LimeLog.info("GameManager is not supported by device")
                return
            }

            val gameMode =
                if (isInterruptible) GameState.MODE_GAMEPLAY_INTERRUPTIBLE else GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE
            val currentGameState = gameManager.gameMode

            if (isStreaming) {
                if (currentGameState != gameMode) {
                    // Set game mode only if it's different from the current state
                    gameManager.setGameState(GameState(false, gameMode))
                } else {
                    LimeLog.info("GameMode: Game mode already set to $gameMode")
                }
            } else {
                // Reset game mode if not streaming
                gameManager.setGameState(GameState(false, GameState.MODE_NONE))
            }
        }
    }

    // Helper function to set the activity game mode state
    fun setActivityGameModeState(activity: ComponentActivity) {
        setGameModeStatus(activity, isStreaming = false, isInterruptible = false)
    }

    // Helper function to notify stream connecting
    fun notifyStreamConnecting(context: Context) {
        setGameModeStatus(context, isStreaming = true, isInterruptible = true)
    }

    // Helper function to notify stream connected
    fun notifyStreamConnected(context: Context) {
        setGameModeStatus(context, isStreaming = true, isInterruptible = false)
    }

    // Helper function to notify stream entering PiP
    fun notifyStreamEnteringPiP(context: Context) {
        setGameModeStatus(context, isStreaming = true, isInterruptible = true)
    }

    // Helper function to notify stream exiting PiP
    fun notifyStreamExitingPiP(context: Context) {
        setGameModeStatus(context, isStreaming = true, isInterruptible = false)
    }

    // Helper function to notify stream ended
    fun notifyStreamEnded(context: Context) {
        setGameModeStatus(context, isStreaming = false, isInterruptible = false)
    }

    // Helper function to set the locale
    @SuppressLint("AppBundleLocaleChanges") // False positive language split false is set in build.gradle.kts
    @Suppress("DEPRECATION")
    fun setLocale(context: Context) {
        val localeTag = PreferenceKeys.fromPreferences(context).language

        if (localeTag != Constants.DEFAULT_LANGUAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val localeManager = context.getSystemService(LocaleManager::class.java)
                val localeList = LocaleList.forLanguageTags(localeTag)

                if (localeManager.applicationLocales != localeList) {
                    localeManager.applicationLocales = localeList
                }
            } else {
                val locale = if (localeTag.contains("-")) {
                    val parts = localeTag.split("-")
                    Locale(parts[0], parts[1])
                } else {
                    Locale(localeTag)
                }

                Locale.setDefault(locale)

                val resources = context.resources
                val configuration = Configuration(resources.configuration)
                configuration.setLocale(locale)

                resources.updateConfiguration(configuration, resources.displayMetrics)
            }
        }
    }

    // Helper function to show the decoder crash dialog
    fun reportDecoderCrash(activity: ComponentActivity, viewModel: MainViewModel) {

        val prefs = PreferenceKeys.fromPreferences(activity)

        // Check if we need to show the dialog
        if (prefs.crashCount != 0 && prefs.crashCount != prefs.lastNotifiedCrashCount) {
            // Show different dialogs based on crash frequency
            applicationScope.launch(Dispatchers.Main) {
                if (prefs.crashCount % 3 == 0) {
                    viewModel.updateCrashState(true)
                    viewModel.updateCrashCount(prefs.crashCount)
                } else {
                    viewModel.updateCrashState(true)
                }
            }
        }
    }

    fun resetStreamingSettings(context: Context) {

        applicationScope.launch {
            context.dataStore.edit { preferences ->
                preferences.remove(stringPreferencesKey("video_format"))
                preferences.remove(stringPreferencesKey("list_resolution_fps"))
                preferences.remove(stringPreferencesKey("list_resolution"))
                preferences.remove(stringPreferencesKey("list_fps"))
                preferences.remove(stringPreferencesKey("seekbar_bitrate"))
                preferences.remove(stringPreferencesKey("seekbar_bitrate_kbps"))
                preferences.remove(stringPreferencesKey("checkbox_unlock_fps"))
                preferences.remove(stringPreferencesKey("checkbox_full_range"))
            }
        }
    }
}