package com.starlight.ui.helpers

import android.content.ComponentName
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.starlight.R
import com.starlight.computers.ComputerManagerListener
import com.starlight.computers.ComputerManagerService
import com.starlight.games.IgdbRepository
import com.starlight.models.MainViewModel
import com.starlight.navigation.Screens
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.NvHTTP
import com.starlight.nvstream.http.PairingManager
import com.starlight.preferences.PreferenceKeys
import com.starlight.utils.Constants.DATABASE_PREF
import com.starlight.utils.ExtendedServiceConnection
import com.starlight.utils.LimeLog
import com.starlight.utils.Starlight.Companion.applicationScope
import com.starlight.utils.Starlight.Companion.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.StringReader
import kotlin.time.Duration.Companion.milliseconds

/**
 * Created by Kevin Andrews on 16/02/2026.
 */

class DataCollectionHelper(var viewModel: MainViewModel, var uiContext: ComponentActivity) {

    private lateinit var computer: ComputerDetails
    private var lastRawApplist: String? = null
    private lateinit var game: IgdbRepository
    private var collectionJob: Job? = null
    private var binderJob: Job? = null
    var managerBinder: ComputerManagerService.ComputerManagerBinder? = null
    var poller: ComputerManagerService.ApplistPoller? = null

    val serviceConnection: ExtendedServiceConnection =
        object : ExtendedServiceConnection() {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val localBinder = service as ComputerManagerService.ComputerManagerBinder

                // Wait in a coroutine to avoid stalling the UI
                binderJob?.cancel()
                binderJob = applicationScope.launch(Dispatchers.Default) {

                    // Wait for the binder to be ready
                    localBinder.waitForReady()

                    // Get the computer object
                    computer = localBinder.getComputer(viewModel.uuidString!!)!!

                    managerBinder = localBinder

                    // Start updates
                    startComputerUpdates()

                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                managerBinder = null
            }
        }

    init {
        viewModel.canGoBack = false
    }

    // Start polling for computer updates
    fun startComputerUpdates() {
        // Don't start polling if we're not bound
        if (managerBinder == null) {
            return
        }
        managerBinder!!.startPolling(ComputerManagerListener { details ->
            // Don't care about other computers
            if (!details.uuid.equals(viewModel.uuidString!!, ignoreCase = true)) {
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
                    viewModel.getNavController()?.popBackStack()
                }
                return@ComputerManagerListener
            }

            // Close immediately if the PC is no longer paired
            if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairingManager.PairState.PAIRED) {
                applicationScope.launch(Dispatchers.Main) {
                    // Display a toast to the user and quit the activity
                    Toast.makeText(
                        uiContext,
                        uiContext.resources.getText(R.string.scut_not_paired),
                        Toast.LENGTH_SHORT
                    ).show()
                    viewModel.getNavController()?.popBackStack()
                }
                return@ComputerManagerListener
            }

            // App list is the same or empty
            if (details.rawAppList == null || details.rawAppList == lastRawApplist) {

                return@ComputerManagerListener
            }

            lastRawApplist = details.rawAppList
            applicationScope.launch(Dispatchers.Main) {
                updateDatabaseWithAppList(details.rawAppList!!)
            }
        })
        if (poller == null) {
            poller = managerBinder!!.createAppListPoller(computer)
        }
        poller?.start()
    }

    fun stopComputerUpdates() {
        poller?.stop()
        managerBinder?.stopPolling()
        collectionJob?.cancel()
        binderJob?.cancel()
    }

    //Check api key and create database
    private fun updateDatabaseWithAppList(rawAppList: String) {

        val prefs = PreferenceKeys.fromPreferences(uiContext)

        game = IgdbRepository(uiContext, computer, viewModel.uuidString!!)

        if (!prefs.databaseEnabled) {
            // Cancel any existing collection job
            collectionJob?.cancel()

            // Database updates: Using coroutines for efficient background processing
            collectionJob = applicationScope.launch {
                try {
                    val appList = NvHTTP.getAppListByReader(StringReader(rawAppList))

                    uiContext.dataStore.edit { preferences ->
                        preferences[booleanPreferencesKey(DATABASE_PREF)] = true
                    }

                    appList.forEach { app ->
                        game.syncGameInfo(app)
                        delay(2000.milliseconds)
                    }
                    LimeLog.info("Loaded applist into database")

                    withContext(Dispatchers.Main) {
                        delay(2000.milliseconds)
                        doAppList(computer = computer, newlyPaired = false, showHiddenGames = false)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun doAppList(
        computer: ComputerDetails,
        newlyPaired: Boolean,
        showHiddenGames: Boolean
    ) {
        when {
            computer.state == ComputerDetails.State.OFFLINE -> {
                Toast.makeText(
                    uiContext,
                    uiContext.resources.getString(R.string.error_pc_offline),
                    Toast.LENGTH_SHORT
                ).show()
                viewModel.canGoBack = true
                return
            }

            managerBinder == null -> {
                Toast.makeText(
                    uiContext,
                    uiContext.resources.getString(R.string.error_manager_not_running),
                    Toast.LENGTH_LONG
                ).show()
                viewModel.canGoBack = true
                return
            }

            else -> {
                viewModel.setIsMainView(true)
                viewModel.pairState = newlyPaired
                viewModel.computer = computer
                viewModel.showHiddenApps = showHiddenGames
                viewModel.uuidString = computer.uuid
                viewModel.name = computer.name

                viewModel.getNavController()
                    ?.navigate(if (viewModel.getIsLeanbackEnabled()) Screens.MainScreen.route else Screens.AppViewScreen.route) {
                        viewModel.getNavController()?.graph?.startDestinationRoute?.let { route ->
                            popUpTo(route) {
                                inclusive = false
                            }
                        }
                        launchSingleTop = true
                        restoreState = true
                    }

                stopComputerUpdates()

                viewModel.getNavController()?.previousBackStackEntry
                    ?.savedStateHandle
                    ?.set("open_screen", true)
            }
        }
    }

}