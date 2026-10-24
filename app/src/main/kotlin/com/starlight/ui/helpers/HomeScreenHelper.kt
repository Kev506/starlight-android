@file:Suppress("SameParameterValue")

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
import com.starlight.games.IgdbRepository
import com.starlight.models.DbVideoItem
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

class HomeScreenHelper(
    var viewModel: MainViewModel,
    var gameViewModel: GameViewModel,
    var uiContext: ComponentActivity,
    uuid: String,
    name: String,
    pair: Boolean,
    hidden: Boolean
) {

    private var shortcutHelper: ShortcutHelper
    private var poller: ApplistPoller? = null
    private var lastRawApplist: String? = null
    private var updated: Boolean = false
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

    // Update the hidden apps list
    private suspend fun updateHiddenApps(
        hideNow: Boolean,
        isShown: Boolean,
        selectedApp: AppComputerObject?
    ) {
        val hiddenAppIdStringSet = hiddenAppIds.map { it.toString() }.toMutableSet()

        putHiddenAppsSet(viewModel.uuidString!!, hiddenAppIdStringSet)

        appViewListRowAdapter?.updateItems(allApps!!.toMutableList(), hiddenAppIds)

        if (hideNow && isShown) {
            (uiContext as? HomeScreenCallBack)?.reloadMainViewScreen()
        } else {
            selectedApp?.let { it.isHidden = hiddenAppIds.contains(it.app?.id) }
        }
    }

    suspend fun putHiddenAppsSet(uuid: String, hiddenAppsSet: Set<String>) {
        PreferenceKeys.putHiddenAppsSetSync(uuid, hiddenAppsSet, uiContext)
    }

    suspend fun handleHideApp(app: AppComputerObject, isChecked: Boolean): Boolean {
        val visible: Boolean
        if (isChecked) {
            hiddenAppIds.remove(app.app?.id)
            visible = false
        } else {
            app.app?.id?.let { hiddenAppIds.add(it) }
            visible = true
        }

        updateHiddenApps(true, visible, app)
        return true
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

                        (uiContext as? HomeScreenCallBack)?.updateViewLoading(true)
                        Toast.makeText(
                            uiContext,
                            uiContext.resources.getString(R.string.applist_refresh_msg),
                            Toast.LENGTH_LONG
                        ).show()

                        applicationScope.launch {
                            delay(2500.milliseconds)
                            (uiContext as? HomeScreenCallBack)?.updateViewLoading(false)
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

    //Check if Database needs additions
    private fun updateDatabase(appList: List<NvApp>) {

        uiContext.let { view ->
            if (doesDatabaseExist(view)) {
                val dbHandler =
                    GameDataBaseManager.getInstance(view) ?: return
                if (!dbHandler.isDatabaseEmpty()) {
                    val databaseGameCount = dbHandler.getGamesCount()
                    val appCount = appList.size.toLong()
                    if (databaseGameCount < appCount) {
                        val gameRepo = IgdbRepository(
                            view,
                            viewModel.computer!!,
                            viewModel.uuidString!!
                        )
                        applicationScope.launch {
                            appList.forEach { app ->
                                gameRepo.fetchAndConnectIgdb(app)
                                delay(1000.milliseconds)
                            }
                        }
                    }
                }
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

        applicationScope.launch {
            if (doesDatabaseExist(uiContext.applicationContext)) {
                val dbHandler =
                    GameDataBaseManager.getInstance(uiContext.applicationContext)
                if (dbHandler != null && !dbHandler.isDatabaseEmpty()) {
                    updateDatabase(appList)
                }
            }
        }

        applicationScope.launch(Dispatchers.Main) {
            for (app in appList) {

                viewModel.videos.addAll(getGameVideos(AppComputerObject(app), uiContext))

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
                    appViewListRowAdapter?.addApp(AppComputerObject(app))
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
                                            (uiContext as? HomeScreenCallBack)?.hideFavoritesButton()
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
                                            (uiContext as? HomeScreenCallBack)?.hideFavoritesButton()
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

                    // Remove the app from the database
                    deleteRemovedGames(appList)

                    updated = true

                    continue
                }
                i++
            }
        }
    }

    fun getGameVideos(app: AppComputerObject?, context: Context): List<DbVideoItem> {
        val dbHandler = GameDataBaseManager.getInstance(context) ?: return emptyList()
        val appData = dbHandler.getAppData(app?.app?.id.toString())

        return if (!appData.videos.isNullOrEmpty() && appData.videos!!.trim() != "null") {
            dbHandler.fetchVideosById(app?.app?.id.toString())
        } else {
            emptyList()
        }
    }

    // Helper method to delete removed games from the database
    private fun deleteRemovedGames(appList: List<NvApp>) {
        if (doesDatabaseExist(uiContext)) {
            val dbHandler =
                GameDataBaseManager.getInstance(uiContext) ?: return

            if (!dbHandler.isDatabaseEmpty()) {
                val gameList = dbHandler.queryAllGames()
                val removedApps = gameList.filter { game ->
                    !appList.any { app -> app.appName.equals(game.title, ignoreCase = true) }
                }
                removedApps.forEach { removedGame ->
                    dbHandler.deleteGameEntryByName(removedGame.title!!)
                }
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
        (uiContext as? HomeScreenCallBack)?.loadAppDetails(
            app!!,
            uuid!!,
            computer!!,
            managerBinder!!,
            false
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
                            (uiContext as? HomeScreenCallBack)?.hideFavoritesButton()
                        }
                    }
                }
            } else {
                app?.app?.let {
                    favAppIds.add(it)
                    if (isGoogleTV or isGoogleTVStreamer) {
                        Toast.makeText(
                            uiContext,
                            app.app?.appName + " " + uiContext.getString(R.string.app_added_to_favorites),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        applicationScope.launch {
                            shortcutHelper.reportGameAddedToHome(viewModel.computer, app.app)
                        }
                    }
                }
                applicationScope.launch {
                    saveArrayList(FAVORITES_PREF, favAppIds)
                }
                (uiContext as? HomeScreenCallBack)?.showFavoritesButton()
            }
        } else {
            if (isChecked) {
                app?.app?.let {
                    try {
                        sunFavAppIds.removeIf { it.id == app.app!!.id }
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
                            (uiContext as? HomeScreenCallBack)?.hideFavoritesButton()
                        }
                    }
                }
            } else {
                app?.app?.let {
                    sunFavAppIds.add(it)
                    if (isGoogleTV or isGoogleTVStreamer) {
                        Toast.makeText(
                            uiContext,
                            app.app?.appName + " " + uiContext.getString(R.string.app_added_to_favorites),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        applicationScope.launch {
                            shortcutHelper.reportGameAddedToHome(viewModel.computer, app.app)
                        }
                    }
                }
                applicationScope.launch {
                    saveArrayList(SUN_FAVORITES_PREF, sunFavAppIds)
                }
                (uiContext as? HomeScreenCallBack)?.showFavoritesButton()
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

    fun refreshAppList(): Boolean {
        viewModel.showHiddenApps = true
        (uiContext as? HomeScreenCallBack)?.reloadMainViewScreen(viewModel.showHiddenApps)
        return true
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

    interface HomeScreenCallBack {
        fun reloadMainViewScreen()
        fun updateViewLoading(loading: Boolean)
        fun hideFavoritesButton()
        fun showFavoritesButton()
        fun loadAppDetails(
            app: AppComputerObject,
            uuid: String,
            computer: ComputerDetails,
            managerBinder: ComputerManagerBinder,
            favs: Boolean
        )

        fun reloadMainViewScreen(hidden: Boolean)
    }
}