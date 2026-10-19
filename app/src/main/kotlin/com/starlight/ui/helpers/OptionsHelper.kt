package com.starlight.ui.helpers

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.core.view.DisplayCompat
import androidx.datastore.preferences.core.stringPreferencesKey
import com.starlight.R
import com.starlight.games.GameDataBaseManager
import com.starlight.models.MainViewModel
import com.starlight.preferences.PreferenceKeys
import com.starlight.ui.adapters.AppAdapter.Companion.favAppIds
import com.starlight.ui.adapters.AppAdapter.Companion.sunFavAppIds
import com.starlight.utils.Constants.FAVORITES_PREF
import com.starlight.utils.Constants.SUN_FAVORITES_PREF
import com.starlight.utils.LimeLog
import com.starlight.utils.Starlight.Companion.applicationScope
import com.starlight.utils.Starlight.Companion.getDeviceName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class OptionsHelper(
    val viewModel: MainViewModel,
    val uiContext: ComponentActivity
) {

    private var tvChannelHelper: TvChannelHelper? = TvChannelHelper(uiContext)

    data class TvItem(val title: String, val imageResourceId: Int)

    init {
        loadDataAsync()
    }

    fun loadDataAsync() {
        applicationScope.launch {

            val tempUiList = mutableListOf<TvItem>()

            val prefConfig = PreferenceKeys.fromPreferences(uiContext)

            val cacheSize = CacheHelper.getDirSizeString(uiContext.applicationContext)
            val hdrSupported = isHdrSupported()

            val dbExists = doesDatabaseExist(uiContext.applicationContext)

            // --- Common items ---
            tempUiList.add(
                TvItem(
                    uiContext.getString(R.string.settings),
                    R.drawable.ic_settings
                )
            )

            if (!viewModel.getIsComputerScreen() && prefConfig.leanback) {
                val launcherName = viewModel.name
                if ((launcherName != "Sunshine" && favAppIds.isNotEmpty()) ||
                    (launcherName == "Sunshine" && sunFavAppIds.isNotEmpty())
                ) {
                    tempUiList.add(
                        TvItem(
                            uiContext.getString(R.string.clear_favorites),
                            R.drawable.baseline_delete_sweep_24
                        )
                    )
                }
            }

            // --- Conditional HDR item ---
            if (hdrSupported) {
                val isHdrEnabled = prefConfig.enableHdr
                val hdrText = if (isHdrEnabled) R.string.hdr_on else R.string.hdr_off
                val hdrIcon =
                    if (isHdrEnabled) R.drawable.ic_baseline_hdr_on_24 else R.drawable.ic_baseline_hdr_off_24
                tempUiList.add(TvItem(uiContext.getString(hdrText), hdrIcon))
            }

            // --- Other common items ---
            tempUiList.add(TvItem(uiContext.getString(R.string.support), R.drawable.ic_help))
            tempUiList.add(
                TvItem(
                    uiContext.getString(R.string.cache, cacheSize),
                    R.drawable.baseline_cached_24
                )
            )

            if (!viewModel.getIsComputerScreen() && dbExists) {
                tempUiList.add(
                    TvItem(
                        uiContext.getString(R.string.database),
                        R.drawable.baseline_update_24
                    )
                )
            }

            tempUiList.add(
                TvItem(
                    uiContext.getString(R.string.exit),
                    R.drawable.baseline_exit_to_app_24
                )
            )

            withContext(Dispatchers.Main) {
                viewModel.items.clear()
                viewModel.items.addAll(tempUiList)
            }
        }
    }

    private fun isHdrSupported(): Boolean {
        val displayManager =
            uiContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return false
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            DisplayCompat.getMode(uiContext, display)
                .toMode()?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) == true
        } else {
            @Suppress("DEPRECATION")
            display.hdrCapabilities?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) == true
        }
    }

    private fun doesDatabaseExist(context: Context): Boolean {
        val dbFile: File = context.getDatabasePath(GameDataBaseManager.DB_NAME)
        return dbFile.exists()
    }

    fun handleClearFavorites() {

        val isSunshine = viewModel.name == "Sunshine"
        val dynamicList = if (isSunshine) sunFavAppIds else favAppIds
        val key = if (isSunshine) SUN_FAVORITES_PREF else FAVORITES_PREF

        applicationScope.launch {
            dynamicList.forEach { app ->
                if (!isGoogleTV && !isGoogleTVStreamer) {
                    try {
                        tvChannelHelper?.deleteProgram(viewModel.computer!!, app)
                    } catch (e: Exception) {
                        LimeLog.warning("Failed to delete program ${e.message}")
                    }
                }
            }
            dynamicList.clear()

            withContext(Dispatchers.Main) {
                (uiContext as? OptionsCallback)?.onFavoritesCleared()
                loadDataAsync()
            }

            deleteArrayList(key)
        }
    }

    suspend fun deleteArrayList(key: String) {
        PreferenceKeys.deleteArrayList(stringPreferencesKey(key), uiContext)
    }

    private val isGoogleTV: Boolean
        get() = getDeviceName() == "Google Chromecast"

    private val isGoogleTVStreamer: Boolean
        get() = getDeviceName() == "Google TV Streamer"

    interface OptionsCallback {
        fun onFavoritesCleared()
        fun onSettingsLaunched()
        fun onHelpLaunched()
        fun onExitLaunched()
    }
}