package com.starlight.ui.helpers

import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.starlight.R
import com.starlight.activities.MainView
import com.starlight.binding.PlatformBinding
import com.starlight.computers.ComputerManagerService
import com.starlight.models.MainViewModel
import com.starlight.navigation.Screens
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.HostHttpResponseException
import com.starlight.nvstream.http.NvApp
import com.starlight.nvstream.http.NvHTTP
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParserException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.UnknownHostException

object ServerHelper {

    var isAppRunning = false

    // Helper function to get the current address from a computer
    @Throws(IOException::class)
    fun getCurrentAddressFromComputer(computer: ComputerDetails): ComputerDetails.AddressTuple {
        return computer.activeAddress ?: throw IOException("No active address for ${computer.name}")
    }

    // Helper function to create a PC shortcut intent
    fun createPcShortcutIntent(parent: ComponentActivity, computer: ComputerDetails): Intent {
        val intent = Intent(parent, MainView::class.java).apply {
            putExtra("NAME", computer.name)
            putExtra("UUID", computer.uuid)
            action = Intent.ACTION_DEFAULT
        }
        return intent
    }

    // Helper function to create an app shortcut intent
    fun createAppShortcutIntent(parent: ComponentActivity, computer: ComputerDetails, app: NvApp): Intent {
        val intent = Intent(parent, MainView::class.java).apply {
            putExtra("NAME", computer.name)
            putExtra("UUID", computer.uuid)
            putExtra("AppName", app.appName)
            putExtra("AppId", app.id.toString())
            putExtra("HDR", app.isHdrSupported)
            action = Intent.ACTION_DEFAULT
        }
        return intent
    }

    // Helper function to start a game
    fun doStartStream(
        parent: ComponentActivity,
        app: NvApp,
        computer: ComputerDetails,
        binder: ComputerManagerService.ComputerManagerBinder,
        viewModel: MainViewModel
    ) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            applicationScope.launch(Dispatchers.Main) {
                Toast.makeText(
                    parent,
                    parent.resources.getString(R.string.pair_pc_offline),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        createGameStart(app, computer, binder, viewModel)
    }

    fun createGameData(
        app: NvApp,
        computer: ComputerDetails,
        binder: ComputerManagerService.ComputerManagerBinder,
        viewModel: MainViewModel
    ) {
        viewModel.managerBinder = binder
        viewModel.computer = computer
        viewModel.uniqueIdString = binder.uniqueId
        viewModel.uuidString = computer.uuid
        viewModel.appId = app.id.toString()
        viewModel.appName = app.appName
        viewModel.isHdrSupported = app.isHdrSupported!!
        viewModel.app = app
        viewModel.pcName = computer.name
        viewModel.cert = computer.serverCert?.encoded
        viewModel.setIsMenuRequired(false)
    }

    fun createGameStart(
        app: NvApp,
        computer: ComputerDetails,
        binder: ComputerManagerService.ComputerManagerBinder,
        viewModel: MainViewModel
    ) {
        viewModel.managerBinder = binder
        viewModel.computer = computer
        viewModel.uniqueIdString = binder.uniqueId
        viewModel.uuidString = computer.uuid
        viewModel.appId = app.id.toString()
        viewModel.appName = app.appName
        viewModel.isHdrSupported = app.isHdrSupported!!
        viewModel.app = app
        viewModel.pcName = computer.name
        viewModel.cert = computer.serverCert?.encoded
        viewModel.setIsMenuRequired(false)

        if (viewModel.getTVScreenState() && !viewModel.getResumedSession()) {
            viewModel.getNavController()?.navigate(Screens.GameStreamingScreen.route) {
                popUpTo(Screens.DetailsScreen.route) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        } else {
            viewModel.getNavController()?.navigate(Screens.GameStreamingScreen.route) {
                popUpTo(if (viewModel.getIsLeanbackEnabled()) Screens.MainScreen.route else Screens.AppViewScreen.route) {
                    inclusive = false
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // Helper function to quit an app
    fun doQuit(
        parent: ComponentActivity,
        computer: ComputerDetails,
        app: NvApp,
        binder: ComputerManagerService.ComputerManagerBinder,
        onComplete: Job?
    ) {

        val quittingMessage = if (isAppRunning) {
            parent.getString(R.string.applist_quit_app)
        } else {
            parent.getString(R.string.applist_quit_app) + " " + app.appName
        }

        Toast.makeText(parent, quittingMessage, Toast.LENGTH_SHORT).show()

        applicationScope.launch {
            var message: String
            try {
                val httpConn = NvHTTP(
                    getCurrentAddressFromComputer(computer),
                    computer.httpsPort,
                    binder.uniqueId,
                    computer.serverCert,
                    PlatformBinding.getCryptoProvider(parent)
                )

                message = if (!isAppRunning) {
                    if (httpConn.quitApp()) {
                        parent.getString(R.string.applist_quit_success) + " " + app.appName
                    } else {
                        parent.getString(R.string.applist_quit_fail) + " " + app.appName
                    }
                } else {
                    if (httpConn.quitApp()) {
                        parent.getString(R.string.applist_quit_success)
                    } else {
                        parent.getString(R.string.applist_quit_fail)
                    }
                }
            } catch (e: HostHttpResponseException) {
                message = handleHttpResponseException(parent, e)
            } catch (_: UnknownHostException) {
                message = parent.getString(R.string.error_unknown_host)
            } catch (_: FileNotFoundException) {
                message = parent.getString(R.string.error_404)
            } catch (e: IOException) {
                message = e.message.toString()
                e.printStackTrace()
            } catch (e: XmlPullParserException) {
                message = e.message.toString()
                e.printStackTrace()
            } finally {
                onComplete?.cancel()
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(parent, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Helper function to handle exceptions
    private fun handleHttpResponseException(
        parent: ComponentActivity,
        e: HostHttpResponseException
    ): String {
        return if (e.getErrorCode() == 599) {
            parent.getString(R.string.this_session_wasn_t_started_by_this_device) +
                    parent.getString(R.string.so_it_cannot_be_quit_end_streaming_on_the_original) +
                    parent.getString(R.string.device_or_the_pc_itself_and_try_again_error_code)
        } else {
            e.getErrorMessage()
        }
    }
}