package com.starlight.ui.helpers

import android.content.ComponentName
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.starlight.R
import com.starlight.binding.PlatformBinding
import com.starlight.binding.crypto.AndroidCryptoProvider
import com.starlight.computers.ComputerManagerService
import com.starlight.models.MainViewModel
import com.starlight.navigation.Screens
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.NvHTTP
import com.starlight.nvstream.http.PairingManager
import com.starlight.nvstream.http.PairingManager.PairState
import com.starlight.nvstream.wol.WakeOnLanSender
import com.starlight.preferences.PreferenceKeys
import com.starlight.ui.adapters.PcAdapter
import com.starlight.ui.grids.assets.DiskAssetLoader
import com.starlight.utils.ComputerObject
import com.starlight.utils.ExtendedServiceConnection
import com.starlight.utils.Starlight.Companion.applicationScope
import com.starlight.utils.Starlight.Companion.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.net.UnknownHostException

class ComputerHelper(var viewModel: MainViewModel, var uiContext: ComponentActivity) {

    private var shortcutHelper: ShortcutHelper? = null
    private var pcAdapter: PcAdapter? = null
    private var message: String? = null
    private var pauseUpdates = false
    private var success = false
    var runningPolling = false
    var managerBinder: ComputerManagerService.ComputerManagerBinder? = null

    val serviceConnection: ExtendedServiceConnection =
        object : ExtendedServiceConnection() {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val localBinder = service as ComputerManagerService.ComputerManagerBinder

                // Wait in a separate thread to avoid stalling the UI
                applicationScope.launch(Dispatchers.Default) {

                    // Wait for the binder to be ready
                    localBinder.waitForReady()

                    // Now make the binder visible
                    managerBinder = localBinder

                    // Start updates
                    startComputerUpdates()

                    // Force a keypair to be generated early to avoid discovery delays
                    AndroidCryptoProvider(uiContext).clientCertificate
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                managerBinder = null
            }
        }

    init {

        pcAdapter = PcAdapter(viewModel)
        shortcutHelper = ShortcutHelper(uiContext)

        // Allow floating expanded PiP overlays while browsing PCs
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                uiContext.setShouldDockBigOverlays(false)
            }
        }

        if (pcAdapter?.getCount() == 0) {
            viewModel.updateNoPcFound(true)
        } else {
            viewModel.updateNoPcFound(false)
        }
    }

    // Start polling for computer updates
    fun startComputerUpdates() {
        managerBinder?.let { binder ->
            if (!runningPolling) {
                pauseUpdates = false
                binder.startPolling { details ->
                    if (!pauseUpdates) {

                        applicationScope.launch(Dispatchers.Main) {
                            updateComputer(details)
                            viewModel.setIsComputerScreen(true)
                        }

                        // Add a launcher shortcut for this PC (off the main thread to prevent ANRs)
                        if (details.pairState == PairState.PAIRED) {
                            shortcutHelper?.createAppViewShortcutForOnlineHost(details)
                        }
                    }
                }

                runningPolling = true
            }
        }
    }

    // Stop polling for computer updates
    fun stopPolling(wait: Boolean) {
        applicationScope.launch(Dispatchers.Default) { stopComputerUpdates(wait) }
    }

    // Stop polling for computer updates
    suspend fun stopComputerUpdates(wait: Boolean) {
        managerBinder?.let { binder ->
            if (runningPolling) {
                pauseUpdates = true
                binder.stopPolling()
                if (wait) {
                    binder.waitForPollingStopped()
                }
                runningPolling = false
            }
        }
    }

    fun doPair(computer: ComputerDetails) {
        // Early return if conditions are not met
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(uiContext, R.string.pair_pc_offline, Toast.LENGTH_SHORT).show()
            return
        }

        val manager = managerBinder ?: run {
            Toast.makeText(uiContext, R.string.error_manager_not_running, Toast.LENGTH_LONG)
                .show()
            return
        }

        Toast.makeText(uiContext, R.string.pairing, Toast.LENGTH_SHORT).show()

        applicationScope.launch(Dispatchers.Default) {
            try {
                // Stop updates and wait while pairing
                stopPolling(true)

                val httpConn = NvHTTP(
                    ServerHelper.getCurrentAddressFromComputer(computer),
                    computer.httpsPort, manager.uniqueId,
                    computer.serverCert,
                    PlatformBinding.getCryptoProvider(uiContext)
                )

                when (httpConn.pairState()) {
                    PairState.PAIRED -> {
                        success = true
                    }

                    else -> {
                        val pinStr = PairingManager.generatePinString()
                        val pinMessage =
                            uiContext.resources.getString(R.string.pair_pairing_msg) + " " + pinStr + "\n\n"

                        // Show pairing dialog on the main thread
                        withContext(Dispatchers.Main) {

                            viewModel.setDialogValue(true)
                            viewModel.setDialogImage(R.drawable.baseline_private_connectivity_24)
                            viewModel.setDialogTitle(
                                uiContext.getString(R.string.pair_pairing_title)
                            )
                            viewModel.setDialogMessage(pinMessage)
                            viewModel.setConfirmButtonLabel(
                                uiContext.getString(android.R.string.ok)
                            )
                            viewModel.setCancelButtonLabel(
                                uiContext.getString(R.string.cancel)
                            )
                            viewModel.setIsDelete(false)
                            viewModel.setIsPairing(true)
                            viewModel.setIsCrash(false)
                            viewModel.setIsHelp(false)
                            viewModel.setIsCancelRequired(true)
                            viewModel.setDismissOnBack(true)

                            // Continue pairing process after dialog is shown
                            launch(Dispatchers.IO) {
                                val pm = httpConn.getPairingManager()
                                when (pm.pair(httpConn.getServerInfo(true), pinStr)) {
                                    PairState.PIN_WRONG -> message =
                                        uiContext.resources.getString(R.string.pair_incorrect_pin)

                                    PairState.FAILED -> message = when {
                                        computer.runningGameId != 0 -> uiContext.resources.getString(
                                            R.string.pair_pc_ingame
                                        )

                                        else -> uiContext.resources.getString(R.string.pair_fail)
                                    }

                                    PairState.ALREADY_IN_PROGRESS -> message =
                                        uiContext.resources.getString(R.string.pair_already_in_progress)

                                    PairState.PAIRED -> {
                                        success = true
                                        // Pin certificate and invalidate state
                                        manager.getComputer(computer.uuid!!)?.serverCert =
                                            pm.getPairedCert()
                                        manager.invalidateStateForComputer(computer.uuid!!)
                                        // No toast needed, will open app list
                                    }

                                    else -> { /* Should be no other values */
                                    }
                                }

                                // Dismiss dialog and show toast or open app list on main thread
                                withContext(Dispatchers.Main) {
                                    viewModel.setDialogValue(false)
                                    message?.let {
                                        Toast.makeText(uiContext, it, Toast.LENGTH_LONG)
                                            .show()
                                    }
                                    if (success) {
                                        doAppList(
                                            computer,
                                            newlyPaired = true,
                                            showHiddenGames = false
                                        )
                                    } else {
                                        startComputerUpdates()
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    when (e) {
                        is UnknownHostException -> Toast.makeText(
                            uiContext,
                            R.string.error_unknown_host,
                            Toast.LENGTH_LONG
                        ).show()

                        is FileNotFoundException -> Toast.makeText(
                            uiContext,
                            R.string.error_404,
                            Toast.LENGTH_LONG
                        ).show()

                        else -> {
                            e.printStackTrace()
                            Toast.makeText(uiContext, e.message, Toast.LENGTH_LONG).show()
                        }
                    }
                    startComputerUpdates()
                }
            }
        }
    }

    fun doWakeOnLan(computer: ComputerDetails) {
        when {
            computer.state == ComputerDetails.State.ONLINE -> {
                Toast.makeText(
                    uiContext,
                    R.string.wol_pc_online,
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            computer.macAddress == null -> {
                Toast.makeText(
                    uiContext,
                    R.string.wol_no_mac,
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            else -> {
                // Using a coroutine for more efficient background threading
                applicationScope.launch(Dispatchers.Default) {
                    val message = try {
                        WakeOnLanSender.sendWolPacket(computer)
                        R.string.wol_waking_msg // Directly use resource ID
                    } catch (_: IOException) {
                        R.string.wol_fail // Directly use resource ID
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(uiContext, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun doAppList(
        computer: ComputerDetails, newlyPaired: Boolean,
        showHiddenGames: Boolean
    ) {

        val prefs = PreferenceKeys.fromPreferences(uiContext)

        // Early return for offline computer
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Toast.makeText(
                uiContext,
                uiContext.resources.getString(R.string.error_pc_offline), Toast.LENGTH_SHORT
            ).show()
            return
        }

        // Early return if managerBinder is null
        managerBinder ?: run {
            Toast.makeText(
                uiContext,
                uiContext.resources.getString(R.string.error_manager_not_running),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Define a function to start MainView
        fun startMainView() {
            viewModel.setIsMainView(true)
            viewModel.pairState = newlyPaired
            viewModel.computer = computer
            viewModel.showHiddenApps = showHiddenGames
            viewModel.uuidString = computer.uuid
            viewModel.name = computer.name

            viewModel.getNavController()
                ?.navigate(if (viewModel.getIsLeanbackEnabled()) Screens.MainScreen.route else Screens.AppViewScreen.route)
        }

        prefs.databaseEnabled.let {
            if (!it) {
                viewModel.setIsMainView(false)
                startDataCollection(computer)
            } else {
                startMainView()
            }
        }
    }

    fun startDataCollection(
        computer: ComputerDetails
    ) {
        // Update the Nav Bar
        (uiContext as? ComposeCallback)?.updateNavBar()

        (uiContext as? ComposeCallback)?.startDataCollection(computer.uuid!!)
    }

    // Helper function to remove a computer
    suspend fun removeComputer(details: ComputerDetails) {

        // Remove the computer from the manager
        managerBinder?.removeComputer(details)
        DiskAssetLoader(uiContext).deleteAssetsForComputer(details.uuid)

        // Delete hidden games preference value
        uiContext.dataStore.edit { preferences ->
            details.uuid?.let { preferences.remove(stringPreferencesKey(it)) }
        }

        pcAdapter?.getCount()?.let {
            for (i in 0..<it) {
                val computer = pcAdapter?.get(i)

                if (details == computer?.details) {
                    // Disable or delete shortcuts referencing this PC
                    shortcutHelper?.disableComputerShortcut(
                        details,
                        uiContext.resources.getString(R.string.scut_deleted_pc)
                    )

                    pcAdapter?.removeComputer(computer)

                    if (pcAdapter?.getCount() == 0) {
                        // Show no PCs found compose view
                        viewModel.updateNoPcFound(true)
                    }

                    viewModel.triggerReload()

                    break
                }
            }
        }
    }

    private fun updateComputer(details: ComputerDetails) {

        var existingEntry: ComputerObject? = null

        pcAdapter?.getCount()?.let {
            for (i in 0..<it) {
                val computer = pcAdapter?.get(i)

                // Check if this is the same computer
                if (details.uuid.equals(computer?.details!!.uuid)) {
                    existingEntry = computer
                    break
                }
            }
        }

        if (existingEntry != null) {
            existingEntry.details = details
            viewModel.computer = details
        } else {
            pcAdapter?.addComputer(ComputerObject(details))
            // Hide no PCs found compose view
            viewModel.updateNoPcFound(false)
            viewModel.computer = details
        }
        pcAdapter?.updateComputer(details)
    }

    interface ComposeCallback {
        fun updateNavBar()
        fun startDataCollection(uuid: String)
    }
}