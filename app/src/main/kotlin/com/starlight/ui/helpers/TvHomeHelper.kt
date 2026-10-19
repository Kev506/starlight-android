package com.starlight.ui.helpers

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.datastore.preferences.core.stringPreferencesKey
import com.starlight.R
import com.starlight.activities.MainView
import com.starlight.computers.ComputerManagerService
import com.starlight.models.MainViewModel
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.NvApp
import com.starlight.nvstream.http.PairingManager
import com.starlight.nvstream.wol.WakeOnLanSender
import com.starlight.preferences.PreferenceKeys
import com.starlight.utils.ExtendedServiceConnection
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Created by Kevin Andrews on 17/09/2026.
 */

class TvHomeHelper(
    var viewModel: MainViewModel,
    var activity: ComponentActivity
) {

    private val intentStack = ArrayList<Intent>()
    private var wakeHostTries = 10
    var managerBinder: ComputerManagerService.ComputerManagerBinder? = null
    private var tvChannelHelper: TvChannelHelper? = null
    var hostComputer: ComputerDetails? = null

    // Service connection
    val serviceConnection: ExtendedServiceConnection =
        object : ExtendedServiceConnection() {
            @SuppressLint("RestrictedApi")
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val localBinder = service as ComputerManagerService.ComputerManagerBinder

                // Wait in a separate thread to avoid stalling the UI
                applicationScope.launch(Dispatchers.IO) {

                    // Wait for the binder to be ready
                    localBinder.waitForReady()

                    // Get the binder uniqueId
                    viewModel.uniqueIdString = localBinder.uniqueId

                    // Now make the binder visible
                    managerBinder = localBinder

                    // Get the computer object
                    val retrievedComputer = handleComputer(localBinder) ?: return@launch
                    hostComputer = retrievedComputer

                    // Force CMS to repoll this machine
                    managerBinder?.invalidateStateForComputer(hostComputer!!.uuid!!)

                    // Start polling and handle the computer details
                    managerBinder?.startPolling { details ->
                        applicationScope.launch(Dispatchers.IO) {
                            handleComputerDetails(details)
                        }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                managerBinder = null
            }
        }

    init {
        tvChannelHelper = TvChannelHelper(activity)
        viewModel.isFavs = true

        if (validateInput(viewModel.uuidString, viewModel.appId)) {
            if (!viewModel.appId.isNullOrEmpty()) {
                viewModel.app =
                    NvApp(viewModel.appName!!, viewModel.appId!!.toInt(), viewModel.isHdrSupported, true)
            }
            viewModel.setProgressDialog(true)
        }
    }

    // Helper function to handle computer details
    private suspend fun handleComputerDetails(details: ComputerDetails) {
        if (!details.uuid.equals(viewModel.uuidString, ignoreCase = true)) {
            return
        }

        if (details.state == ComputerDetails.State.OFFLINE && details.macAddress != null && --wakeHostTries >= 0) {
            try {
                WakeOnLanSender.sendWolPacket(hostComputer!!)
                managerBinder?.invalidateStateForComputer(hostComputer!!.uuid!!)
                return
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        if (details.state != ComputerDetails.State.UNKNOWN) {
            applicationScope.launch(Dispatchers.Main) {
                viewModel.setProgressDialog(false)

                if (managerBinder == null) {
                    activity.finish()
                    return@launch
                }

                when (details.state) {
                    ComputerDetails.State.ONLINE -> handleOnlineState(details, managerBinder)
                    ComputerDetails.State.OFFLINE -> handleOfflineState()
                    else -> handlePairingError(details)
                }
            }
        }
    }

    // Helper function to handle pairing error
    private fun handlePairingError(details: ComputerDetails) {
        if (details.pairState != PairingManager.PairState.PAIRED) {
            // Computer not paired - display an error dialog
            viewModel.setDialogValue(true)
            viewModel.setDialogMessage(activity.getString(R.string.scut_not_paired))
            viewModel.setDialogTitle(activity.getString(R.string.conn_error_title))
            viewModel.setDialogImage(R.drawable.baseline_error_outline_24)
            viewModel.setCancelButtonLabel(activity.getString(android.R.string.ok))
            viewModel.setIsCancelRequired(false)
        }
    }

    // Helper function to handle offline state
    private fun handleOfflineState() {
        applicationScope.launch(Dispatchers.Main) {
            // Computer offline - display an error dialog
            viewModel.setDialogValue(true)
            viewModel.setDialogMessage(activity.getString(R.string.error_pc_offline))
            viewModel.setDialogTitle(activity.getString(R.string.conn_error_title))
            viewModel.setDialogImage(R.drawable.baseline_error_outline_24)
            viewModel.setCancelButtonLabel(activity.getString(android.R.string.ok))
            viewModel.setIsCancelRequired(false)
        }
    }

    // Helper function to quit the game job
    @SuppressLint("RestrictedApi")
    fun quitAndStartJob(): Job {
        return applicationScope.launch(Dispatchers.Main) {
            viewModel.setTVScreenState(true)
            viewModel.setAppRunning(false)
            managerBinder?.stopPolling()

            withContext(Dispatchers.IO) {
                val nvApp = PreferenceKeys.getParcelable(stringPreferencesKey("App"), null, activity)
                val programId = tvChannelHelper?.getWatchNextPrograms(
                    activity,
                    "" + nvApp!!.id
                )
                if (programId?.id != null) {
                    quitWatchNextApp(nvApp)
                }
            }
        }
    }

    // Helper function to quit the watch next app if it exists
    private fun quitWatchNextApp(selectedApp: NvApp?): Job {
        return applicationScope.launch(Dispatchers.IO) {
            tvChannelHelper?.deleteWatchNext(
                selectedApp!!
            )
        }
    }

    // Helper function to handle PC view launch
    private fun handlePcViewLaunch() {

        activity.finish()

        // Add the PC view at the back (and setDefault the task)
        var i = Intent(activity, MainView::class.java)
        i.action = Intent.ACTION_MAIN
        i.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        intentStack.add(i)

        i = Intent(activity.intent)
        i.setClass(
            activity,
            MainView::class.java
        )
        intentStack.add(i)

        // Now start the activities
        activity.startActivities(intentStack.toArray(arrayOf()))

    }

    // Helper function to handle online state
    private fun handleOnlineState(
        details: ComputerDetails,
        managerBinder: ComputerManagerService.ComputerManagerBinder?
    ) {
        if (details.pairState == PairingManager.PairState.PAIRED) {
            if (viewModel.app != null) {
                handleAppLaunch(details, managerBinder)
            } else {
                handlePcViewLaunch()
            }
        } else {
            handlePairingError(details)
        }
    }

    // Helper function to handle app launch
    @SuppressLint("RestrictedApi")
    private fun handleAppLaunch(details: ComputerDetails, managerBinder: ComputerManagerService.ComputerManagerBinder?) {

        val shortcutHelper = ShortcutHelper(activity)
        hostComputer?.let { shortcutHelper.reportComputerShortcutUsed(it) }

        applicationScope.launch(Dispatchers.Main) {
            if (details.runningGameId == 0 || details.runningGameId == (viewModel.app
                    ?: return@launch).id
            ) {
                val programId = tvChannelHelper?.getWatchNextPrograms(
                    activity,
                    "" + viewModel.app!!.id
                )
                if (programId?.id != null) {
                    viewModel.setResumeSession(true)
                    viewModel.setProgressDialog(false)
                } else {
                    viewModel.setTVScreenState(true)
                    managerBinder?.stopPolling()
                    viewModel.setResumeSession(false)
                }

                if (viewModel.getResumedSession()) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.resuming_session),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                ServerHelper.createGameData(
                    viewModel.app ?: return@launch,
                    details,
                    managerBinder!!,
                    viewModel
                )

                // Stop polling we don't need the UI updating after app launch
                managerBinder.stopPolling()

            } else {

                viewModel.setAppRunning(true)

                // Stop polling we don't need the UI updating when showing the dialog.
                managerBinder?.stopPolling()

                viewModel.setTvAlertDialog(true)
                viewModel.setDialogMessage(activity.getString(R.string.applist_quit_confirmation))
                viewModel.setDialogTitle(activity.getString(R.string.confirm_quit))
                viewModel.setDialogImage(R.drawable.baseline_stop_circle_24)
                viewModel.setCancelButtonLabel(activity.getString(R.string.quit))
                viewModel.setIsCancelRequired(true)
                viewModel.setIsQuitting(true)
            }
        }
    }

    // Helper function to handle computer
    private suspend fun handleComputer(localBinder: ComputerManagerService.ComputerManagerBinder): ComputerDetails? {
        val computer = localBinder.getComputer(viewModel.uuidString!!)
        if (computer == null) {
            applicationScope.launch(Dispatchers.Main) {
                viewModel.setDialogValue(true)
                viewModel.setDialogMessage(activity.getString(R.string.scut_pc_not_found))
                viewModel.setDialogTitle(activity.getString(R.string.conn_error_title))
                viewModel.setDialogImage(R.drawable.baseline_error_outline_24)
                viewModel.setCancelButtonLabel(activity.getString(android.R.string.ok))
                viewModel.setIsCancelRequired(false)

                viewModel.setProgressDialog(false)

                managerBinder?.let {
                    activity.unbindService(serviceConnection)
                    managerBinder = null
                }
            }
            return null
        }
        return computer
    }

    // Helper function to validate input
    fun validateInput(uuidString: String?, appIdString: String?): Boolean {

        // Validate UUID
        if (uuidString.isNullOrEmpty()) {
            showErrorDialog(activity.getString(R.string.scut_invalid_uuid))
            return false
        }

        try {
            UUID.fromString(uuidString)
        } catch (_: IllegalArgumentException) {
            showErrorDialog(activity.getString(R.string.scut_invalid_uuid))
            return false
        }

        // Validate App ID (if provided)
        if (!appIdString.isNullOrEmpty()) {
            try {
                appIdString.toInt()
            } catch (_: NumberFormatException) {
                showErrorDialog(activity.getString(R.string.scut_invalid_app_id))
                return false
            }
        }

        return true
    }

    // Helper function to show an error dialog
    private fun showErrorDialog(message: String) {
        viewModel.setDialogValue(true)
        viewModel.setDialogMessage(message)
        viewModel.setDialogTitle(activity.getString(R.string.conn_error_title))
        viewModel.setDialogImage(R.drawable.baseline_error_outline_24)
        viewModel.setCancelButtonLabel(activity.getString(android.R.string.ok))
        viewModel.setIsCancelRequired(false)
    }
}