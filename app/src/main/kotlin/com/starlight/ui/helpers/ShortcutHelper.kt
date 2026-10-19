package com.starlight.ui.helpers

import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import androidx.activity.ComponentActivity
import androidx.datastore.preferences.core.stringPreferencesKey
import com.starlight.R
import com.starlight.computers.ComputerManagerService
import com.starlight.models.MainViewModel
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.NvApp
import com.starlight.preferences.PreferenceKeys
import com.starlight.utils.Constants
import com.starlight.utils.Starlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ShortcutHelper(private val context: ComponentActivity) {

    private val sm: ShortcutManager = context.getSystemService(ShortcutManager::class.java)
    private val tvChannelHelper: TvChannelHelper = TvChannelHelper(context)

    // Get the favorites
    suspend fun getFavorites(name: String) {

        val favorites: List<NvApp> = getArrayList(Constants.FAVORITES_PREF) ?: emptyList()
        val sunFavorites: List<NvApp> = getArrayList(Constants.SUN_FAVORITES_PREF) ?: emptyList()

        if (name != "Sunshine") {
            if (favorites.isNotEmpty()) {
                TvFavAppIds.clear()
                TvFavAppIds.addAll(favorites.toMutableList())
            }
        } else {
            if (sunFavorites.isNotEmpty()) {
                TvSunFavAppIds.clear()
                TvSunFavAppIds.addAll(sunFavorites.toMutableList())
            }
        }
    }

    suspend fun getArrayList(key: String): MutableList<NvApp>? {
        return PreferenceKeys.getArrayList(stringPreferencesKey(key), context)
    }

    // Helper function to reap shortcuts for dynamic add
    private fun reapShortcutsForDynamicAdd() {
        val dynamicShortcuts = sm.dynamicShortcuts
        while (dynamicShortcuts.size >= sm.maxShortcutCountPerActivity) {
            // Find the shortcut with the highest rank using 'maxByOrNull'
            val shortcutToRemove = dynamicShortcuts.maxByOrNull { it.rank }

            // Remove the shortcut with the highest rank if found
            shortcutToRemove?.let {
                sm.removeDynamicShortcuts(listOf(it.id))
            }
        }
    }

    // Helper function to get all shortcuts
    private val allShortcuts: List<ShortcutInfo>
        get() = sm.dynamicShortcuts + sm.pinnedShortcuts

    // Helper function to get info for ID
    private fun getInfoForId(id: String): ShortcutInfo? = allShortcuts.find { it.id == id }

    // Helper function to check if existing dynamic shortcut
    private fun isExistingDynamicShortcut(id: String): Boolean =
        sm.dynamicShortcuts.any { it.id == id }

    // Helper function to report computer shortcut used
    fun reportComputerShortcutUsed(computer: ComputerDetails) {
        computer.uuid?.let {
            if (getInfoForId(it) != null) {
                sm.reportShortcutUsed(it)
            }
        }
    }

    // Helper function to report game added to home
    suspend fun reportGameAddedToHome(computer: ComputerDetails?, app: NvApp?) {
        tvChannelHelper.createTvChannel(computer!!)
        tvChannelHelper.addGameToChannel(computer, app!!)
    }


    // Helper function to add game to watch next
    fun addWatchNextShortcut(activity: ComponentActivity, gameScreenHelper: GameScreenHelper, viewModel: MainViewModel, computer: ComputerDetails, app: NvApp) {
        tvChannelHelper.addGameToWatchNext(computer, app)
        gameScreenHelper.stopConnection()
        if (viewModel.getTVScreenState()) {
            activity.finish()
        } else {
            if (viewModel.isFavs) {
                viewModel.setScreenState("FavoritesScreen")
                viewModel.getNavController()?.popBackStack()
            } else {
                viewModel.setScreenState("MainScreen")
                viewModel.getNavController()?.popBackStack()
            }
        }
    }

    // Helper function to create shortcut
    fun createAppViewShortcut(computer: ComputerDetails, forceAdd: Boolean, newlyPaired: Boolean) {

        val sinfo = ShortcutInfo.Builder(context, computer.uuid)
            .setIntent(ServerHelper.createPcShortcutIntent(context, computer))
            .setShortLabel(computer.name!!)
            .setLongLabel(computer.name!!)
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_pc_scut))
            .build()

        val existingSinfo = getInfoForId(computer.uuid!!)
        if (existingSinfo != null) {
            sm.updateShortcuts(listOf(sinfo))
            sm.enableShortcuts(listOf(computer.uuid))
        }

        if (!isExistingDynamicShortcut(computer.uuid!!)) {
            if (forceAdd || sm.dynamicShortcuts.size < sm.maxShortcutCountPerActivity) {
                reapShortcutsForDynamicAdd()
                sm.addDynamicShortcuts(listOf(sinfo))
            }
        }
        if (newlyPaired) {
            tvChannelHelper.createTvChannel(computer)
            tvChannelHelper.requestChannelOnHomeScreen(computer)
        }
    }

    // Helper function to create pc shortcut
    fun createAppViewShortcutForOnlineHost(details: ComputerDetails) {
        createAppViewShortcut(details, forceAdd = false, newlyPaired = false)
    }

    // Helper function to get shortcut ID for game
    private fun getShortcutIdForGame(computer: ComputerDetails, app: NvApp): String {
        return computer.uuid + app.id
    }

    // Helper function to disable computer shortcut
    fun disableComputerShortcut(computer: ComputerDetails, reason: CharSequence?) {
        tvChannelHelper.deleteChannel(computer)

        // Combine shortcut removal operations
        val shortcutIdsToDisable = mutableListOf<String>()
        getInfoForId(computer.uuid!!)?.let { shortcutIdsToDisable.add(computer.uuid!!) }

        // Filter out the computer's shortcuts
        shortcutIdsToDisable.addAll(allShortcuts.filter { it.id.startsWith(computer.uuid!!) }
            .map { it.id })

        // Disable all shortcuts at once
        if (shortcutIdsToDisable.isNotEmpty()) {
            sm.disableShortcuts(shortcutIdsToDisable, reason)
        }
    }

    // Helper function to disable app shortcut
    fun disableAppShortcut(computer: ComputerDetails, app: NvApp, reason: CharSequence?) {
        tvChannelHelper.deleteProgram(computer, app)
        val id = getShortcutIdForGame(computer, app)
        if (getInfoForId(id) != null) {
            sm.disableShortcuts(listOf(id), reason)
        }
    }

    // Helper function to remove watch next shortcut
    fun removeWatchNextShortcut(app: NvApp) {
        tvChannelHelper.deleteWatchNext(app)
    }

    // Helper function to remove watch next shortcut
    fun removeWatchNextShortcutById(appId: Int) {
        tvChannelHelper.deleteWatchNextById(appId)
    }

    // Helper function to enable app shortcut
    fun enableAppShortcut(computer: ComputerDetails, app: NvApp) {
        val id = getShortcutIdForGame(computer, app)
        if (getInfoForId(id) != null) {
            sm.enableShortcuts(listOf(id))
        }
    }

    // Helper function to quit game
    fun quitGame(
        activity: ComponentActivity,
        gameScreenHelper: GameScreenHelper,
        computer: ComputerDetails?,
        app: NvApp?,
        managerBinder: ComputerManagerService.ComputerManagerBinder?,
        viewModel: MainViewModel
    ): Job {

        gameScreenHelper.stopConnection()
        viewModel.setPerfOverlay(false)

        return Starlight.applicationScope.launch(Dispatchers.Main) {
            if (viewModel.getIsStopped()) {
                ServerHelper.isAppRunning = false
                ServerHelper.doQuit(
                    activity, computer!!, app!!,
                    managerBinder!!, quitJob(app)
                )
                if (viewModel.getTVScreenState()) {
                    activity.finish()
                } else {
                    if (viewModel.isFavs) {
                        viewModel.setScreenState("FavoritesScreen")
                        viewModel.getNavController()?.popBackStack()
                    } else {
                        viewModel.setScreenState("MainScreen")
                        viewModel.getNavController()?.popBackStack()
                    }
                }
            }
        }
    }


    // Helper function to remove watch next shortcut
    private fun quitJob(selectedApp: NvApp?): Job {
        return Starlight.applicationScope.launch(Dispatchers.Main) {
            removeWatchNextShortcut(
                selectedApp!!
            )
        }
    }

    companion object {
        val TvSunFavAppIds: MutableList<NvApp> = mutableListOf()
        val TvFavAppIds: MutableList<NvApp> = mutableListOf()
    }

}