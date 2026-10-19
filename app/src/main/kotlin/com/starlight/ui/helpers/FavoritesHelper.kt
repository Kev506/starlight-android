package com.starlight.ui.helpers

import android.content.ComponentName
import android.content.Context
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.datastore.preferences.core.stringPreferencesKey
import com.starlight.R
import com.starlight.computers.ComputerManagerListener
import com.starlight.computers.ComputerManagerService.ApplistPoller
import com.starlight.computers.ComputerManagerService.ComputerManagerBinder
import com.starlight.games.GameDataBaseManager
import com.starlight.models.GameViewModel
import com.starlight.models.MainViewModel
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.NvApp
import com.starlight.nvstream.http.NvHTTP
import com.starlight.nvstream.http.PairingManager
import com.starlight.preferences.PreferenceKeys
import com.starlight.ui.adapters.AppAdapter
import com.starlight.ui.adapters.AppAdapter.Companion.allApps
import com.starlight.ui.adapters.AppAdapter.Companion.favAppIds
import com.starlight.ui.adapters.AppAdapter.Companion.sunFavAppIds
import com.starlight.utils.AppComputerObject
import com.starlight.utils.Constants.FAVORITES_PREF
import com.starlight.utils.Constants.SUN_FAVORITES_PREF
import com.starlight.utils.ExtendedServiceConnection
import com.starlight.utils.LimeLog
import com.starlight.utils.LimeLog.info
import com.starlight.utils.Starlight.Companion.applicationScope
import com.starlight.utils.Starlight.Companion.getDeviceName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Created by Kevin Andrews on 05/05/2026.
 */

class FavoritesHelper(
    var viewModel: MainViewModel,
    var gameViewModel: GameViewModel,
    var uiContext: ComponentActivity,
    uuid: String,
    name: String,
    pair: Boolean,
    hidden: Boolean
) {

    private var shortcutHelper: ShortcutHelper
    private var updated: Boolean = false
    private var poller: ApplistPoller? = null
    private var lastRawApplist: String? = null
    var managerBinder: ComputerManagerBinder? = null
    var appViewListRowAdapter: AppAdapter? = null
    var suspendGridUpdates = false
    var lastRunningAppId = 0

    var hiddenAppIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    val serviceConnection: ExtendedServiceConnection =
        object : ExtendedServiceConnection() {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val localBinder = service as ComputerManagerBinder

                applicationScope.launch(Dispatchers.Default) {

                    localBinder.waitForReady()

                    viewModel.computer = localBinder.getComputer(viewModel.uuidString!!)
                    viewModel.uniqueIdString = localBinder.uniqueId

                    withContext(Dispatchers.Main) {
                        shortcutHelper.createAppViewShortcut(
                            viewModel.computer ?: return@withContext,
                            true,
                            viewModel.pairState
                        )
                        shortcutHelper.reportComputerShortcutUsed(viewModel.computer!!)
                    }

                    appViewListRowAdapter = try {
                        AppAdapter(
                            viewModel.computer!!, localBinder.uniqueId,
                            viewModel.showHiddenApps, uiContext
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                        uiContext.finish()
                        return@launch
                    }
                    appViewListRowAdapter?.updateHiddenApps(hiddenAppIds, true)

                    managerBinder = localBinder

                    withContext(Dispatchers.Main) {
                        managerBinder?.let { viewModel.setBinder(it) }
                    }

                    populateAppGridWithCache()

                    startComputerUpdates()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                managerBinder = null
            }
        }

    fun startComputerUpdates() {

        if (managerBinder == null) {
            return
        }

        managerBinder!!.startPolling(ComputerManagerListener { details ->
            // Do nothing if updates are suspended
            if (suspendGridUpdates) {
                return@ComputerManagerListener
            }

            if (!details.uuid.equals(viewModel.uuidString, ignoreCase = true)) {
                return@ComputerManagerListener
            }
            if (details.state == ComputerDetails.State.OFFLINE) {
                applicationScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        uiContext,
                        uiContext.resources.getText(R.string.lost_connection),
                        Toast.LENGTH_SHORT
                    ).show()
                    uiContext.finish()
                }
                return@ComputerManagerListener
            }

            if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairingManager.PairState.PAIRED) {
                applicationScope.launch(Dispatchers.Main) {

                    shortcutHelper.disableComputerShortcut(
                        details,
                        uiContext.resources.getString(R.string.scut_not_paired)
                    )

                    Toast.makeText(
                        uiContext,
                        uiContext.resources.getText(R.string.scut_not_paired),
                        Toast.LENGTH_SHORT
                    ).show()
                    uiContext.finish()
                }
                return@ComputerManagerListener
            }

            if (details.rawAppList == null || details.rawAppList == lastRawApplist) {

                if (details.runningGameId != lastRunningAppId) {
                    lastRunningAppId = details.runningGameId
                    updateUiWithServerinfo(details)
                }
                return@ComputerManagerListener
            }
            lastRunningAppId = details.runningGameId
            lastRawApplist = details.rawAppList
            try {
                updateUiWithAppList(NvHTTP.getAppListByReader(StringReader(details.rawAppList)))
                updateUiWithServerinfo(details)
            } catch (e: XmlPullParserException) {
                e.printStackTrace()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            if (updated) {
                updateList()
            }
        })
        if (poller == null) {
            poller = managerBinder!!.createAppListPoller(viewModel.computer!!)
        }
        poller?.start()
    }

    fun stopComputerUpdates() {
        poller?.stop()
        managerBinder?.stopPolling()
        appViewListRowAdapter?.cancelQueuedOperations()
    }

    private fun updateList() {
        applicationScope.launch(Dispatchers.Main) {
            viewModel.triggerReload()
            Toast.makeText(
                uiContext,
                uiContext.getString(R.string.app_list_updated), Toast.LENGTH_SHORT
            ).show()
        }
    }

    init {
        viewModel.uuidString = uuid
        viewModel.name = name
        viewModel.pairState = pair
        viewModel.showHiddenApps = hidden

        shortcutHelper = ShortcutHelper(uiContext)

        applicationScope.launch {
            val hiddenAppsPrefs =
                PreferenceKeys.getHiddenAppsSetSync(viewModel.uuidString, HashSet(), uiContext)
            hiddenAppIds.clear()
            hiddenAppsPrefs.forEach { hiddenAppIdStr ->
                hiddenAppIdStr.toIntOrNull()?.let { id ->
                    hiddenAppIds.add(id)
                }
            }
        }
    }

    // Handle the game check
    fun handleGameCheck() {
        viewModel.selectedApp.observe(uiContext) { selectedApp ->
            try {
                selectedApp?.let {
                    if (doesDatabaseExist(uiContext)) {
                        gameViewModel.loadGame(it)
                    } else {
                        gameViewModel.loadGame(it)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Check for cached app list
    private fun populateAppGridWithCache() {
        try {
            // Try to load from cache
            lastRawApplist = viewModel.uuidString?.let {
                CacheHelper
                    .openCacheFileForInput(uiContext.cacheDir, "applist", it)
            }?.let {
                CacheHelper.readInputStreamToString(
                    it
                )
            }
            val applist: List<NvApp> = NvHTTP.getAppListByReader(StringReader(lastRawApplist))
            updateUiWithAppList(applist)
            info("Loaded applist from cache")
        } catch (e: Exception) {
            when (e) {
                is IOException,
                is XmlPullParserException ->
                    if (lastRawApplist != null) {
                        LimeLog.warning("Saved applist corrupted: $lastRawApplist")
                        // We'll need to load from the network
                        info("Loading applist from the network")

                        (uiContext as? FavsScreenCallBack)?.updateViewLoading(true)
                        Toast.makeText(
                            uiContext,
                            uiContext.resources.getString(R.string.applist_refresh_msg),
                            Toast.LENGTH_LONG
                        ).show()

                        applicationScope.launch(Dispatchers.Main) {
                            delay(2500.milliseconds)
                            (uiContext as? FavsScreenCallBack)?.updateViewLoading(false)
                        }
                        e.printStackTrace()
                    }

                else -> throw e
            }
        }
    }

    // Update the UI with the server info
    private fun updateUiWithServerinfo(details: ComputerDetails) {

        applicationScope.launch(Dispatchers.Main) {

            viewModel.setLastRunningAppId(details.runningGameId)

            var updated = false

            // Look through our current app list to tag the running app
            for (i in 0 until (appViewListRowAdapter?.size() ?: return@launch)) {
                val existingApp = appViewListRowAdapter?.get(i) as AppComputerObject
                // There can only be one or zero apps running.
                if (existingApp.isRunning == true &&
                    existingApp.app?.id == details.runningGameId
                ) {
                    // This app was running and still is, so we're done now
                    return@launch
                } else if (existingApp.app?.id == details.runningGameId) {
                    // This app wasn't running but now is
                    existingApp.isRunning = true
                    updated = true
                } else if (existingApp.isRunning == true) {
                    // This app was running but now isn't
                    existingApp.isRunning = false
                    updated = true
                }
            }
            if (updated) {
                appViewListRowAdapter!!.updateItems(allApps!!.toMutableList(), hiddenAppIds)
            }
        }

    }

    // Helper method to check if database exists
    private fun doesDatabaseExist(context: Context): Boolean {
        val dbFile: File = context.getDatabasePath(GameDataBaseManager.DB_NAME)
        return dbFile.exists()
    }

    // Update the UI with the app list
    private fun updateUiWithAppList(appList: List<NvApp>) {

        applicationScope.launch(Dispatchers.Main) {
            for (app in appList) {

                var foundExistingApp = false

                for (i in 0 until (appViewListRowAdapter!!.size())) {
                    val existingApp = appViewListRowAdapter!!.get(i)
                    if (existingApp.app?.id == app.id) {
                        if (existingApp.app?.appName != app.appName) {
                            existingApp.app?.appName = app.appName
                            updated = true
                        }
                        foundExistingApp = true
                        break
                    }
                }
                if (!foundExistingApp) {
                    if (viewModel.name != "Sunshine") {
                        for (i in favAppIds.indices) {
                            val favApp = favAppIds[i]
                            if (favApp.id == app.id) {
                                appViewListRowAdapter?.addApp(AppComputerObject(app))
                            }
                        }
                    } else {
                        for (i in sunFavAppIds.indices) {
                            val favApp = sunFavAppIds[i]
                            if (favApp.id == app.id) {
                                appViewListRowAdapter?.addApp(AppComputerObject(app))
                            }
                        }
                    }
                    shortcutHelper.enableAppShortcut(viewModel.computer!!, app)
                    updated = true
                }
            }

            var i = 0
            while (i < appViewListRowAdapter!!.size()) {
                var foundExistingApp = false
                val existingApp = appViewListRowAdapter!!.get(i)

                for ((_, id) in appList) {
                    if (existingApp.app?.id == id) {
                        foundExistingApp = true
                        break
                    }
                }

                if (!foundExistingApp) {
                    if (viewModel.name != "Sunshine") {
                        try {
                            val ifRemoved = favAppIds.removeIf { it == existingApp.app }
                            if (ifRemoved) {
                                existingApp.app?.let {
                                    shortcutHelper.disableAppShortcut(
                                        viewModel.computer!!,
                                        it,
                                        "App removed from PC"
                                    )
                                }
                                withContext(Dispatchers.IO) {
                                    if (getArrayList(FAVORITES_PREF)!!.isEmpty()) {
                                        deleteArrayList(FAVORITES_PREF)
                                        withContext(Dispatchers.Main) {
                                            (uiContext as? FavsScreenCallBack)?.hideFavoritesButton()
                                        }
                                    }
                                }
                            }
                        } catch (_: NullPointerException) {
                            info("favAppIds is null")
                        }
                    } else {
                        try {
                            val ifRemoved = sunFavAppIds.removeIf { it == existingApp.app }
                            if (ifRemoved) {
                                existingApp.app?.let {
                                    shortcutHelper.disableAppShortcut(
                                        viewModel.computer!!,
                                        it,
                                        "App removed from PC"
                                    )
                                }
                                withContext(Dispatchers.IO) {
                                    if (getArrayList(SUN_FAVORITES_PREF)!!.isEmpty()) {
                                        deleteArrayList(SUN_FAVORITES_PREF)
                                        withContext(Dispatchers.Main) {
                                            (uiContext as? FavsScreenCallBack)?.hideFavoritesButton()
                                        }
                                    }
                                }
                            }
                        } catch (_: NullPointerException) {
                            info("sunFavAppIds is null")
                        }
                    }
                    // Remove the app from the adapter
                    appViewListRowAdapter?.removeApp(existingApp)

                    updated = true

                    continue
                }
                i++
            }
        }
    }

    // Helper function to quit the game job
    fun quitJob(selectedApp: AppComputerObject): Job {
        return applicationScope.launch(Dispatchers.Main) {
            suspendGridUpdates = false
            poller?.pollNow()

            viewModel.lastRunningAppId.observe(uiContext) { appId ->
                if (appId != 0) {
                    shortcutHelper.removeWatchNextShortcutById(appId)
                    viewModel.setLastRunningAppId(0)
                } else {
                    shortcutHelper.removeWatchNextShortcut(
                        selectedApp.app!!
                    )
                }
            }

            viewModel.setAppRunning(false)
        }
    }

    // Helper method to show the details screen
    fun showDetails(
        app: AppComputerObject?,
        managerBinder: ComputerManagerBinder?,
        computer: ComputerDetails?,
        uuid: String?
    ): Boolean {
        (uiContext as? FavsScreenCallBack)?.loadAppDetails(
            app!!,
            uuid!!,
            computer!!,
            managerBinder!!,
            true
        )
        return true
    }

    // Helper method to handle the toggle favorite action
    fun handleToggleFavorite(isChecked: Boolean, app: AppComputerObject?) {
        if (viewModel.name != "Sunshine") {
            if (isChecked) {
                app?.app?.let {
                    try {
                        favAppIds.removeIf { it.id == app.app!!.id }
                        appViewListRowAdapter?.removeAppById(app.app!!.id)
                        if (isGoogleTV or isGoogleTVStreamer) {
                            Toast.makeText(
                                uiContext,
                                app.app?.appName + " " + uiContext.getString(R.string.app_removed_from_favorites),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            shortcutHelper.disableAppShortcut(
                                viewModel.computer!!,
                                app.app!!,
                                "removed from favorites"
                            )
                            (uiContext as? FavsScreenCallBack)?.reloadMainViewScreen()
                        }
                    } catch (_: NullPointerException) {
                        info("favAppIds is null")
                    }
                }

                applicationScope.launch {
                    saveArrayList(FAVORITES_PREF, favAppIds)
                    if (getArrayList(FAVORITES_PREF)!!.isEmpty()) {
                        deleteArrayList(FAVORITES_PREF)
                        withContext(Dispatchers.Main) {
                            (uiContext as? FavsScreenCallBack)?.hideFavoritesButton()
                            viewModel.isFavs = false
                            viewModel.getNavController()?.popBackStack()
                            viewModel.setItemSelected(0)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            (uiContext as? FavsScreenCallBack)?.reloadMainViewScreen()
                        }
                    }
                }
            }
        } else {
            if (isChecked) {
                app?.app?.let {
                    try {
                        sunFavAppIds.removeIf { it.id == app.app!!.id }
                        appViewListRowAdapter?.removeAppById(app.app!!.id)
                        if (isGoogleTV or isGoogleTVStreamer) {
                            Toast.makeText(
                                uiContext,
                                app.app?.appName + " " + uiContext.getString(R.string.app_removed_from_favorites),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            shortcutHelper.disableAppShortcut(
                                viewModel.computer!!,
                                app.app!!,
                                "removed from favorites"
                            )
                            (uiContext as? FavsScreenCallBack)?.reloadMainViewScreen()
                        }
                    } catch (_: NullPointerException) {
                        info("sunFavAppIds is null")
                    }
                }
                applicationScope.launch {
                    saveArrayList(SUN_FAVORITES_PREF, sunFavAppIds)
                    if (getArrayList(SUN_FAVORITES_PREF)!!.isEmpty()) {
                        deleteArrayList(SUN_FAVORITES_PREF)
                        withContext(Dispatchers.Main) {
                            (uiContext as? FavsScreenCallBack)?.hideFavoritesButton()
                            viewModel.isFavs = false
                            viewModel.getNavController()?.popBackStack()
                            viewModel.setItemSelected(0)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            (uiContext as? FavsScreenCallBack)?.reloadMainViewScreen()
                        }
                    }
                }
            }
        }
    }

    suspend fun getArrayList(key: String): MutableList<NvApp>? {
        return PreferenceKeys.getArrayList(stringPreferencesKey(key), uiContext)
    }

    suspend fun saveArrayList(key: String, appIds: MutableList<NvApp>) {
        PreferenceKeys.saveArrayList(stringPreferencesKey(key), appIds, uiContext)
    }

    suspend fun deleteArrayList(key: String) {
        PreferenceKeys.deleteArrayList(stringPreferencesKey(key), uiContext)
    }

    // Get the cover art from the database
    suspend fun getGameImage(app: NvApp): Any? = withContext(Dispatchers.IO) {
        val dbHandler = GameDataBaseManager.getInstance(uiContext.applicationContext)
        val appData = dbHandler?.getAppData(app.id.toString())

        val image = if (appData?.coverArt != null) {
            appData.coverArt
        } else if (appData?.fallbackCoverArt != null) {
            appData.fallbackCoverArt
        } else {
            return@withContext null
        }

        return@withContext image!!
    }

    // Helper function to check if the device is a Google TV
    private val isGoogleTV: Boolean
        get() = getDeviceName() == "Google Chromecast"

    // Helper function to check if the device is a Google TV Streamer
    private val isGoogleTVStreamer: Boolean
        get() = getDeviceName() == "Google TV Streamer"

    interface FavsScreenCallBack {
        fun reloadMainViewScreen()
        fun updateViewLoading(loading: Boolean)
        fun hideFavoritesButton()
        fun loadAppDetails(
            app: AppComputerObject,
            uuid: String,
            computer: ComputerDetails,
            managerBinder: ComputerManagerBinder,
            favs: Boolean
        )
    }
}