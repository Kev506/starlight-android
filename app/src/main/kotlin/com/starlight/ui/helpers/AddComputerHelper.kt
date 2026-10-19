package com.starlight.ui.helpers

import android.content.ComponentName
import android.content.res.Resources
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.starlight.R
import com.starlight.computers.ComputerManagerService
import com.starlight.models.MainViewModel
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.NvHTTP
import com.starlight.nvstream.jni.StarBridge
import com.starlight.utils.Constants.CONNECTION_TEST_SERVER
import com.starlight.utils.ExtendedServiceConnection
import com.starlight.utils.LimeLog
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.net.URISyntaxException

class AddComputerHelper(
    var uiContext: ComponentActivity,
    private val resources: Resources, private val viewModel: MainViewModel
) {

    var managerBinder: ComputerManagerService.ComputerManagerBinder? = null
    var addComputerJob: Job? = null
    val computersToAdd = Channel<String>()

    // Helper function to bind service
    val serviceConnection: ExtendedServiceConnection =
        object : ExtendedServiceConnection() {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                managerBinder = service as ComputerManagerService.ComputerManagerBinder
                startAddJob()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                managerBinder = null
            }
        }

    // Helper Function to Check Site-Local Address
    private fun isWrongSubnetSiteLocalAddress(address: String): Boolean {
        return try {
            val targetAddress = InetAddress.getByName(address)
            if (targetAddress !is Inet4Address || !targetAddress.isSiteLocalAddress) {
                LimeLog.warning("isWrongSubnetSiteLocalAddress: Not a site-local address")
                false
            } else {
                // We have a site-local address. Look for a matching local interface.
                val interfaces = NetworkInterface.getNetworkInterfaces().toList()
                var foundMatch = false
                loop@ for (iface in interfaces) {
                    for (addr in iface.interfaceAddresses) {
                        if (addr.address !is Inet4Address || !addr.address.isSiteLocalAddress) {
                            // Skip non-site-local or non-IPv4 addresses
                            continue
                        }

                        val targetAddrBytes = targetAddress.address
                        val ifaceAddrBytes = addr.address.address

                        // Compare prefix to ensure it's the same
                        val prefixLength = addr.networkPrefixLength.toInt()
                        for (i in 0 until prefixLength) {
                            val bitMask = 1 shl (i % 8)
                            if ((ifaceAddrBytes[i / 8].toInt() and bitMask) != (targetAddrBytes[i / 8].toInt() and bitMask)) {
                                continue@loop
                            }
                        }
                        foundMatch = true
                        break
                    }
                    if (foundMatch) break
                }
                !foundMatch
            }
        } catch (e: Exception) {
            // Catch all exceptions because some broken Android devices
            // will throw an NPE from inside getNetworkInterfaces().
            LimeLog.warning("isWrongSubnetSiteLocalAddress:" + e.message)
            e.printStackTrace()
            false
        }
    }

    // Helper Function to Parse Raw User Input
    private fun parseRawUserInputToUri(rawUserInput: String): URI? {
        // Try parsing with "starlight" scheme directly,handling most cases
        try {
            val uri = URI("starlight://$rawUserInput")
            if (uri.host != null && uri.host.isNotEmpty()) {
                return uri
            }
        } catch (_: URISyntaxException) {
            // Ignore and continue
        }

        // Handle potential IPv6 literal input
        if (rawUserInput.contains(":")) {
            try {
                val uri = URI("starlight://[$rawUserInput]")
                if (uri.host != null && uri.host.isNotEmpty()) {
                    return uri
                }
            } catch (_: URISyntaxException) {
                // Ignore and continue
            }
        }

        return null
    }

    // Do Add PC
    private suspend fun doAddPc(rawUserInput: String) = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) {
            viewModel.setLoaderValue(true)
        }

        var success: Boolean
        var wrongSiteLocal = false
        var invalidInput = false

        try {
            val details = ComputerDetails()
            val uri = parseRawUserInputToUri(rawUserInput)

            if (uri?.host != null && uri.host.isNotEmpty()) {
                val host = uri.host
                var port = uri.port
                if (port == -1) {
                    port = NvHTTP.DEFAULT_HTTP_PORT
                }
                details.manualAddress = ComputerDetails.AddressTuple(host, port)
                success = managerBinder!!.addComputerBlocking(details)
                if (!success) {
                    wrongSiteLocal = isWrongSubnetSiteLocalAddress(host)
                }
            } else {
                success = false
                invalidInput = true
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                viewModel.setLoaderValue(false)
            }
            throw e
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            success = false
            invalidInput = true
        }

        // Connectivity Test
        val portTestResult = if (!success && !wrongSiteLocal && !invalidInput) {
            StarBridge.testClientConnectivity(
                CONNECTION_TEST_SERVER,
                443,
                StarBridge.ML_PORT_FLAG_TCP_47984 or StarBridge.ML_PORT_FLAG_TCP_47989
            )
        } else {
            StarBridge.ML_TEST_RESULT_INCONCLUSIVE
        }

        withContext(Dispatchers.Main) {
            viewModel.setLoaderValue(false)
        }

        // Handle Results with Consolidated Dialog Logic
        when {
            invalidInput -> {
                withContext(Dispatchers.Main) {
                    viewModel.setDialogValue(true)
                    viewModel.setDialogMessage(resources.getString(R.string.addpc_unknown_host))
                    viewModel.setDialogImage(R.drawable.baseline_error_outline_24)
                }
            }

            wrongSiteLocal -> {
                withContext(Dispatchers.Main) {
                    viewModel.setDialogValue(true)
                    viewModel.setDialogMessage(resources.getString(R.string.addpc_wrong_sitelocal))
                    viewModel.setDialogImage(R.drawable.baseline_error_outline_24)
                }
            }

            !success -> {
                val dialogText =
                    if (portTestResult != StarBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
                        resources.getString(R.string.nettest_text_blocked)
                    } else {
                        resources.getString(R.string.addpc_fail)
                    }
                withContext(Dispatchers.Main) {
                    viewModel.setDialogValue(true)
                    viewModel.setDialogMessage(dialogText)
                    viewModel.setDialogImage(R.drawable.baseline_error_outline_24)
                }
            }

            else -> { // Success
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        uiContext,
                        resources.getString(R.string.addpc_success),
                        Toast.LENGTH_LONG
                    ).show()
                    (uiContext as? ComposeCallback)?.popStack()
                }
            }
        }
    }

    // Returns true if the event should be eaten
    suspend fun handleDoneEvent(address: String?): Boolean {
        val hostAddress = address?.trim()
        if (hostAddress!!.isEmpty()) {
            Toast.makeText(
                uiContext,
                resources.getString(R.string.addpc_enter_ip),
                Toast.LENGTH_LONG
            ).show()
            return true
        }
        computersToAdd.send(hostAddress)
        return false
    }

    // Start Add Job
    private fun startAddJob()  {
        addComputerJob = applicationScope.launch {
            while (isActive) {
                try {
                    val computer = computersToAdd.receive()
                    doAddPc(computer)
                } catch (e: Exception) {
                    LimeLog.info("AddJob:" + " Job Canceled: ${e.message}")
                    return@launch
                }
            }
        }.apply {
            LimeLog.info("addComputerJob:Started")
        }
    }

    interface ComposeCallback {
        fun popStack()
    }
}