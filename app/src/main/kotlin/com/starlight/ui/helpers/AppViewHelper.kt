package com.starlight.ui.helpers

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.starlight.R
import com.starlight.computers.ComputerManagerListener
import com.starlight.computers.ComputerManagerService.ApplistPoller
import com.starlight.computers.ComputerManagerService.ComputerManagerBinder
import com.starlight.games.GameDataBaseManager
import com.starlight.games.IgdbRepository
import com.starlight.models.MainViewModel
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.NvApp
import com.starlight.nvstream.http.NvHTTP
import com.starlight.nvstream.http.PairingManager
import com.starlight.preferences.PreferenceKeys
import com.starlight.ui.adapters.AppAdapter
import com.starlight.ui.adapters.AppAdapter.Companion.allApps
import com.starlight.utils.AppComputerObject
import com.starlight.utils.ExtendedServiceConnection
import com.starlight.utils.LimeLog
import com.starlight.utils.Starlight.Companion.applicationScope
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
 * Created by Kevin Andrews on 17/04/2026.
 */

@Suppress("SameParameterValue")
class AppViewHelper(
    var viewModel: MainViewModel,
    var uiContext: ComponentActivity,
    uuid: String,
    pairState: Boolean,
    hidden: Boolean
) {

    private var updated: Boolean = false
    private var poller: ApplistPoller? = null
    private var lastRawApplist: String? = null
    private var shortcutHelper: ShortcutHelper
    var hiddenAppIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    var appViewListRowAdapter: AppAdapter? = null
    var suspendGridUpdates = false
    var managerBinder: ComputerManagerBinder? = null
    var lastRunningAppId = 0

    val serviceConnection: ExtendedServiceConnection =
        object : ExtendedServiceConnection() {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val localBinder = service as ComputerManagerBinder

                // Use a coroutine instead of a Thread for better readability and efficiency
                applicationScope.launch(Dispatchers.Default) {
                    // Wait for the binder to be ready
                    localBinder.waitForReady()

                    // Get the computer object
                    viewModel.computer = localBinder.getComputer(viewModel.uuidString!!)
                    viewModel.uniqueIdString = localBinder.uniqueId

                    // Add a launcher shortcut for this PC (forced, since this is user interaction)
                    withContext(Dispatchers.Main) {
                        shortcutHelper.createAppViewShortcut(
                            viewModel.computer ?: return@withContext,
                            true,
                            viewModel.pairState

                        )
                        shortcutHelper.reportComputerShortcutUsed(
                            viewModel.computer ?: return@withContext
                        )
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

                    // Make the binder visible after Adapter is initialized
                    managerBinder = localBinder

                    withContext(Dispatchers.Main) {
                        managerBinder?.let { viewModel.setBinder(it) }
                    }

                    // Load app grid with cached data before starting updates
                    populateAppGridWithCache()

                    // Start polling for updates
                    startComputerUpdates()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                managerBinder = null
            }
        }

    init {

        viewModel.uuidString = uuid
        viewModel.showHiddenApps = hidden
        viewModel.pairState = pairState

        shortcutHelper = ShortcutHelper(uiContext)

        // Allow floating expanded PiP overlays while browsing PCs
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                uiContext.setShouldDockBigOverlays(false)
            }
        }

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

    // Start polling for updates
    fun startComputerUpdates() {
        // Don't start polling if we're not bound or in the foreground
        if (managerBinder == null) {
            return
        }
        managerBinder!!.startPolling(ComputerManagerListener { details ->
            // Do nothing if updates are suspended
            if (suspendGridUpdates) {
                return@ComputerManagerListener
            }

            // Don't care about other computers
            if (!details.uuid.equals(viewModel.uuidString, ignoreCase = true)) {
                return@ComputerManagerListener
            }
            if (details.state == ComputerDetails.State.OFFLINE) {
                // The PC is unreachable now
                applicationScope.launch(Dispatchers.Main) {
                    // Display a toast to the user and quit the activity
                    Toast.makeText(
                        uiContext,
                        uiContext.resources.getText(R.string.lost_connection),
                        Toast.LENGTH_SHORT
                    ).show()
                    uiContext.finish()
                }
                return@ComputerManagerListener
            }

            // Close immediately if the PC is no longer paired
            if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairingManager.PairState.PAIRED) {
                applicationScope.launch(Dispatchers.Main) {
                    // Disable shortcuts referencing this PC for now
                    shortcutHelper.disableComputerShortcut(
                        details,
                        uiContext.resources.getString(R.string.scut_not_paired)
                    )

                    // Display a toast to the user and quit the activity
                    Toast.makeText(
                        uiContext,
                        uiContext.resources.getText(R.string.scut_not_paired),
                        Toast.LENGTH_SHORT
                    ).show()
                    uiContext.finish()
                }
                return@ComputerManagerListener
            }

            // App list is the same or empty
            if (details.rawAppList == null || details.rawAppList == lastRawApplist) {

                // Let's check if the running app ID changed
                if (details.runningGameId != lastRunningAppId) {
                    // Update the currently running game using the app ID
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
            poller = viewModel.computer?.let { managerBinder!!.createAppListPoller(it) }
        }
        poller!!.start()
    }

    // Stop polling for updates
    fun stopComputerUpdates() {
        poller?.stop()
        managerBinder?.stopPolling()
        appViewListRowAdapter?.cancelQueuedOperations()
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
            (uiContext as? AppViewCallBack)?.reloadAppViewScreen()
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
            LimeLog.info("Loaded applist from cache")
        } catch (e: Exception) {
            when (e) {
                is IOException,
                is XmlPullParserException ->
                    if (lastRawApplist != null) {
                        LimeLog.warning("Saved applist corrupted: $lastRawApplist")
                        // We'll need to load from the network
                        LimeLog.info("Loading applist from the network")

                        (uiContext as? AppViewCallBack)?.updateViewLoading(true)
                        Toast.makeText(
                            uiContext,
                            uiContext.resources.getString(R.string.applist_refresh_msg),
                            Toast.LENGTH_LONG
                        ).show()

                        applicationScope.launch(Dispatchers.Main) {
                            delay(2500.milliseconds)
                            (uiContext as? AppViewCallBack)?.updateViewLoading(false)
                        }
                        e.printStackTrace()
                    }

                else -> throw e
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
                                gameRepo.syncGameInfo(app)
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

    // Helper method to update the app list
    private fun updateList() {
        applicationScope.launch(Dispatchers.Main) {
            Toast.makeText(
                uiContext,
                uiContext.getString(R.string.app_list_updated), Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Update the UI with the app list
    private fun updateUiWithAppList(appList: List<NvApp>) {

        applicationScope.launch {
            if (doesDatabaseExist(uiContext.applicationContext)) {
                val dbHandler =
                    GameDataBaseManager.getInstance(uiContext.applicationContext)
                if (!dbHandler?.isDatabaseEmpty()!!) {
                    updateDatabase(appList)
                }
            }
        }

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
                    appViewListRowAdapter!!.addApp(AppComputerObject(app))
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
                    // Remove the app from the adapter
                    appViewListRowAdapter!!.removeApp(existingApp)

                    // Remove the app from the database
                    deleteRemovedGames(appList)

                    updated = true

                    continue
                }
                i++
            }
        }
    }

    fun handleStartOrResume(app: AppComputerObject): Boolean {
        managerBinder?.let {
            viewModel.computer?.let { it1 ->
                app.app?.let { it2 ->
                    ServerHelper.doStartStream(
                        uiContext, it2, it1, it, viewModel
                    )
                }
            }
        }
        return true
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
        }
    }

    // Helper method to show the details screen
    fun showDetails(
        app: AppComputerObject?,
        managerBinder: ComputerManagerBinder?,
        computer: ComputerDetails?,
        uuid: String?
    ): Boolean {
        (uiContext as? AppViewCallBack)?.loadAppDetails(
            app!!,
            uuid!!,
            computer!!,
            managerBinder!!,
            false
        )
        return true
    }

    fun refreshAppList(): Boolean {
        viewModel.showHiddenApps = true
        (uiContext as? AppViewCallBack)?.reloadAppViewScreen(viewModel.showHiddenApps)
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

    interface AppViewCallBack {
        fun reloadAppViewScreen()
        fun updateViewLoading(loading: Boolean)
        fun loadAppDetails(
            app: AppComputerObject,
            uuid: String,
            computer: ComputerDetails,
            managerBinder: ComputerManagerBinder,
            favs: Boolean
        )

        fun reloadAppViewScreen(hidden: Boolean)
    }
}