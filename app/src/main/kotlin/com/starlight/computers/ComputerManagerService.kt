package com.starlight.computers

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.runtime.Stable
import com.starlight.binding.PlatformBinding
import com.starlight.computers.ComputerDatabaseManager.Companion.getInstance
import com.starlight.discovery.DiscoveryService
import com.starlight.discovery.DiscoveryService.DiscoveryServiceBinder
import com.starlight.nvstream.NvConnection
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.ComputerDetails.AddressTuple
import com.starlight.nvstream.http.NvApp
import com.starlight.nvstream.http.NvHTTP
import com.starlight.nvstream.http.PairingManager
import com.starlight.nvstream.mdns.MdnsComputer
import com.starlight.nvstream.mdns.MdnsDiscoveryListener
import com.starlight.ui.helpers.CacheHelper.openCacheFileForOutput
import com.starlight.ui.helpers.CacheHelper.writeStringToOutputStream
import com.starlight.ui.helpers.ServerHelper.getCurrentAddressFromComputer
import com.starlight.utils.ExtendedServiceConnection
import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.severe
import com.starlight.utils.LimeLog.warning
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.StringReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

class ComputerManagerService : Service() {

    private var binder: ComputerManagerBinder = ComputerManagerBinder()
    private var idManager: UniqueIdManager? = null
    private var listener: ComputerManagerListener? = null
    private var discoveryBinder: DiscoveryServiceBinder? = null
    private var isDiscoveryServiceBound = false

    private var pollingActive = false

    private val pollingTuples = ArrayDeque<PollingTuple>()
    private lateinit var networkCallback: NetworkCallback
    private var binderReadySignal = CompletableDeferred<DiscoveryServiceBinder>()
    private val activePollJobs = Collections.synchronizedList(mutableListOf<Job>())
    private val mutex: Mutex = Mutex()

    private val discoveryServiceConnection: ExtendedServiceConnection = object : ExtendedServiceConnection() {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val privateBinder = service as DiscoveryServiceBinder

            // Set us as the event listener
            privateBinder.setListener(createDiscoveryListener())

            // Store the binder
            discoveryBinder = privateBinder

            // Signal that the binder is ready by completing the Deferred object with the binder itself.
            binderReadySignal.complete(privateBinder)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            discoveryBinder = null

            // Create a new Deferred for the next time the service connects.
            binderReadySignal = CompletableDeferred()
        }
    }

    // Returns true if the details object was modified
    @Throws(InterruptedException::class)
    private suspend fun runPoll(details: ComputerDetails, isNewPc: Boolean, offlineCount: Int): Boolean {
        val maxPollTries = if (details.state == ComputerDetails.State.UNKNOWN) {
            INITIAL_POLL_TRIES
        } else {
            OFFLINE_POLL_TRIES
        }

        // Poll the machine
        try {
            if (!pollComputer(details)) {
                if (!isNewPc && offlineCount < maxPollTries) {
                    // Return without calling the listener
                    return false
                }
                details.state = ComputerDetails.State.OFFLINE
            }
        } catch (e: IOException) {
            warning("Poll failed: " + e.message)
            details.state = ComputerDetails.State.OFFLINE
        }

        // Handle online state
        if (details.state == ComputerDetails.State.ONLINE) {
            handleOnlineState(details, isNewPc)
        }

        // Notify listener if needed
        if ((!isNewPc || details.state == ComputerDetails.State.ONLINE) && listener != null) {
            listener!!.notifyComputerUpdated(details)
        }
        return true
    }

    // Handle online state
    private fun handleOnlineState(details: ComputerDetails, isNewPc: Boolean) {
        val existingComputer = dbManager!!.getComputerByUUID(details.uuid!!)

        // Check if it's still in the database
        if (!isNewPc && existingComputer == null) {
            return // It's gone
        }

        // Update or insert the computer details
        if (existingComputer != null) {
            // Combine existing data with new data
            existingComputer.updateComputerDetails(details)
            dbManager!!.updateComputer(existingComputer)
        } else {
            // Populate external address if needed
            populateExternalAddressIfNeeded(details)
            dbManager!!.updateComputer(details)
        }
    }

    // Populate external address if needed
    private fun populateExternalAddressIfNeeded(details: ComputerDetails) {
        if (details.remoteAddress == null) {
            try {
                val addr = InetAddress.getByName(details.activeAddress?.address)
                if (addr.isSiteLocalAddress) {
                    populateExternalAddress(details)
                }
            } catch (_: UnknownHostException) {
                info("Unknown host: " + details.activeAddress?.address)
            }
        }
    }

    // Create a polling thread
    private fun launchPollingJob(tuple: PollingTuple): Job {
        return applicationScope.launch {
            var offlineCount = 0
            while (isActive && pollingActive) {
                try {
                    // Create a new job for each individual poll attempt
                    val pollJob = applicationScope.launch {
                        tuple.networkLock.withLock {
                            // You can now safely call a suspend function from inside this block
                            val isOnline = runPoll(tuple.computer, false, offlineCount)
                            if (!isOnline) {
                                warning("${tuple.computer.name} is offline (try $offlineCount)")
                                offlineCount++
                            } else {
                                tuple.lastSuccessfulPollMs = SystemClock.elapsedRealtimeNanos()
                                offlineCount = 0
                            }
                        }
                    }

                    // Add the job to our tracking list and remove it when it completes
                    activePollJobs.add(pollJob)
                    pollJob.invokeOnCompletion { activePollJobs.remove(pollJob) }

                    // Use the non-blocking delay
                    delay(SERVERINFO_POLLING_PERIOD_MS.milliseconds)
                } catch (e: CancellationException) {
                    info("Polling for ${tuple.computer.name} was cancelled.")
                    throw e
                } catch (_: InterruptedException) {
                    warning("Polling for ${tuple.computer.name} was interrupted. Stopping poll.")
                    break
                }
            }
        }
    }

    // Binder class
    @Stable
    inner class ComputerManagerBinder : Binder() {

        // Start polling
        fun startPolling(listener: ComputerManagerListener) {
            // Polling is active
            pollingActive = true
            this@ComputerManagerService.listener = listener
            discoveryBinder?.startDiscovery(MDNS_QUERY_PERIOD_MS)

            applicationScope.launch {
                mutex.withLock {
                    pollingTuples.forEach { tuple ->
                        val timeSinceLastPoll =
                            SystemClock.elapsedRealtimeNanos() - tuple.lastSuccessfulPollMs
                        if (timeSinceLastPoll > POLL_DATA_TTL_MS) {
                            info("Timing out polled state for ${tuple.computer.name}")
                            tuple.computer.state = ComputerDetails.State.UNKNOWN
                        }

                        listener.notifyComputerUpdated(tuple.computer)

                        // Lazily start the polling job
                        if (tuple.job == null || !tuple.job!!.isActive) {
                            tuple.job = launchPollingJob(tuple)
                        }
                    }
                }
            }
        }

        // Wait for the binder
        suspend fun waitForReady() {
            try {
                // Suspend until the binderReadySignal is completed. This is non-blocking.
                if (discoveryBinder == null) {binderReadySignal.await()
                }
            } catch (e: CancellationException) {
                // This is the standard way coroutines are canceled.
                info("Interrupted waiting for ready")
                // No need to interrupt the thread; just re-throw to allow the coroutine to clean up.
                throw e
            }
        }

        // Wait for polling to stop
        suspend fun waitForPollingStopped() {
            try {
                // Create a snapshot of the list and wait for all jobs in it to complete.
                activePollJobs.toList().joinAll()
            } catch (e: CancellationException) {
                // Handle cancellation gracefully
                info("Interrupted waiting for polling to stop")
                throw e
            }
        }

        // Add a computer blocking
        @Throws(InterruptedException::class)
        suspend fun addComputerBlocking(fakeDetails: ComputerDetails): Boolean {
            return this@ComputerManagerService.addComputerBlocking(fakeDetails)
        }

        // Remove a computer
        fun removeComputer(computer: ComputerDetails) {
            applicationScope.launch {
                this@ComputerManagerService.removeComputer(computer)
            }
        }

        // Stop polling
        fun stopPolling() {
            // Just call the unbind handler to clean up
            onUnbind(null)
        }

        // Create an applist poller
        fun createAppListPoller(computer: ComputerDetails): ApplistPoller {
            return ApplistPoller(computer)
        }

        // Get the unique ID
        val uniqueId: String
            get() = idManager!!.uniqueId!!

        // Get a computer
        suspend fun getComputer(uuid: String): ComputerDetails? {
            mutex.withLock {
                for (tuple in pollingTuples) {
                    if (uuid == tuple.computer.uuid) {
                        return tuple.computer
                    }
                }
            }
            return null
        }

        // Invalidate a computer's state
        suspend fun invalidateStateForComputer(uuid: String) {
            mutex.withLock {
                // Iterate and find the matching tuple
                val tuple = pollingTuples.find { it.computer.uuid == uuid }
                tuple?.let {
                    // We need the network lock to prevent a concurrent poll from wiping this change out
                    it.networkLock.withLock {
                        it.computer.state = ComputerDetails.State.UNKNOWN
                    }
                }
            }
        }
    }

    // Unbind from the service and stop polling
    override fun onUnbind(intent: Intent?): Boolean {
        // Stop mDNS Autodiscovery
        discoveryBinder?.stopDiscovery()
        pollingActive = false

        applicationScope.launch {
            mutex.withLock {
                pollingTuples.forEach { tuple ->
                    // Cancel the job safely. This is non-blocking.
                    tuple.job?.cancel()
                    tuple.job = null
                }
            }
        }

        listener = null
        return false
    }

    // Populate external address
    private fun populateExternalAddress(details: ComputerDetails) {
        var boundToNetwork = false
        val activeNetworkIsVpn = isActiveNetworkVpn(this)
        val connectivityManager =
            getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        // Bind to the underlying network if we're connected to a VPN
        if (activeNetworkIsVpn) {
            bindToUnderlyingNetwork(connectivityManager) { bound ->
                boundToNetwork = bound
            }
        }

        // Perform the STUN request if necessary
        if (!activeNetworkIsVpn || boundToNetwork) {
            resolveExternalAddress(details).let { externalAddress ->
                details.remoteAddress = AddressTuple(externalAddress, details.returnExternalPort())
            }
        }

        // Unbind from the network if necessary
        if (boundToNetwork) {
            connectivityManager.bindProcessToNetwork(null)
        }
    }

    // Helper function to encapsulate network binding logic
    private fun bindToUnderlyingNetwork(
        connectivityManager: ConnectivityManager,
        onBound: (Boolean) -> Unit
    ) {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (capabilities != null) {
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                onBound(connectivityManager.bindProcessToNetwork(network))
            } else {
                onBound(false)
            }
        } else {
            onBound(false)
        }
    }

    // Helper function to encapsulate external address resolution
    private fun resolveExternalAddress(details: ComputerDetails): String? {
        try {
            val stunResolvedAddress =
                NvConnection.findExternalAddressForMdns("stun.starlight-stream.com", 3478)
            if (stunResolvedAddress != null) {
                details.remoteAddress =
                    AddressTuple(stunResolvedAddress, details.returnExternalPort())
            }
            return stunResolvedAddress ?: details.remoteAddress!!.address
        } catch (e: Exception) {
            warning("Failed to resolve external address: " + e.message)
        }
        return null
    }

    // Create a listener for mDNS Autodiscovery
    private fun createDiscoveryListener(): MdnsDiscoveryListener {
        return object : MdnsDiscoveryListener {
            override fun notifyComputerAdded(computer: MdnsComputer) {
                val details = ComputerDetails().apply {
                    computer.localAddress?.let { localAddress ->
                        this.localAddress = AddressTuple(localAddress.hostAddress, computer.port)

                        // Since we're on the same network, we can use STUN to find
                        // our WAN address, which is also very likely the WAN address
                        // of the PC. We can use this later to connect remotely.
                        if (localAddress is Inet4Address) {
                            populateExternalAddress(this)
                        }
                    }
                    computer.ipv6Address?.let { ipv6Address ->
                        this.ipv6Address = AddressTuple(ipv6Address.hostAddress, computer.port)
                    }
                }

                try {
                    // Kick off a blocking serverinfo poll on this machine
                    applicationScope.launch {
                        if (!addComputerBlocking(details)) {
                            warning("Auto-discovered PC failed to respond: $details")
                        }
                    }
                } catch (_: Exception) {
                    info("Interrupted waiting for serverinfo poll")
                }
            }

            override fun notifyDiscoveryFailure(e: Exception) {
                severe("mDNS discovery failed $e")
            }
        }
    }

    // Add a tuple
    private suspend fun addTuple(details: ComputerDetails) {
        mutex.withLock {
            val existingTuple = pollingTuples.find { it.computer.uuid == details.uuid }
            if (existingTuple != null) {
                // Update and start polling if necessary
                existingTuple.computer.updateComputerDetails(details)
                if (pollingActive && existingTuple.job == null) {
                    existingTuple.job = launchPollingJob(existingTuple)
                }
                return
            } else {
                // Create a new tuple and add it to the list
                val newTuple = PollingTuple(details, null)
                if (pollingActive) {
                    newTuple.job = launchPollingJob(newTuple)
                }
                pollingTuples.add(newTuple)
            }
        }
    }

    // Add a computer blocking
    @Throws(InterruptedException::class)
    suspend fun addComputerBlocking(fakeDetails: ComputerDetails): Boolean {    // Bridge the blocking world to the suspend world.
        val success = pollComputer(fakeDetails)

        if (success) {
            // This part runs only if the poll was successful.
            fakeDetails.serverCert = findPinnedCertForComputer(fakeDetails)
            // Since runPoll is also a suspend function,
            // we need to call it within a coroutine context.
            runPoll(fakeDetails, true, 0)
        }

        return if (fakeDetails.state == ComputerDetails.State.ONLINE) {
            info("New PC (${fakeDetails.name}) is UUID ${fakeDetails.uuid}")
            addTuple(fakeDetails)
            true
        } else {
            false
        }
    }

    // Helper function to find pinned cert
    private suspend fun findPinnedCertForComputer(computer: ComputerDetails): X509Certificate? {
        mutex.withLock {
            pollingTuples.forEach { tuple ->
                if (tuple.computer.uuid == computer.uuid) {
                    computer.serverCert = tuple.computer.serverCert
                }
            }
        }
        return computer.serverCert
    }

    // Remove a computer
    suspend fun removeComputer(computer: ComputerDetails) {
        // Remove it from the database
        dbManager?.deleteComputer(computer)

        mutex.withLock {
            pollingTuples.removeIf { it.computer.uuid == computer.uuid }

            pollingTuples.forEach {
                it.job?.cancel()
                it.job = null
            }
        }
    }

    // Try to poll an IP address
    private suspend fun tryPollIp(details: ComputerDetails, address: AddressTuple?): ComputerDetails? {
        // This ensures the network call doesn't block the main thread.
        return withContext(Dispatchers.IO) {
            try {
                // Don't try to poll a null address
                if (address == null) {
                    return@withContext null
                }

                val httpsPort = getHttpsPort(details, address)

                val http = NvHTTP(
                    address,
                    httpsPort,
                    idManager!!.uniqueId,
                    details.serverCert,
                    PlatformBinding.getCryptoProvider(this@ComputerManagerService)
                )

                val isCurrentlyOnline =
                    details.state == ComputerDetails.State.ONLINE && address == details.activeAddress

                val newDetails = http.getComputerDetails(isCurrentlyOnline)

                if (newDetails.uuid == null) {
                    severe("Polling returned no UUID!")
                    return@withContext null
                }
                if (details.uuid != null && details.uuid != newDetails.uuid) {
                    info("Polling returned the wrong PC!")
                    return@withContext null
                }

                // Success! Return the details from this block
                newDetails

            } catch (e: XmlPullParserException) {
                severe("XML parsing error: " + e.message)
                null
            } catch (_: IOException) {
                // Don't log the error, just return null
                null
            }
        }
    }

    // Helper function to determine HTTPS port
    private fun getHttpsPort(details: ComputerDetails, address: AddressTuple?): Int {
        return if (details.state == ComputerDetails.State.ONLINE &&
            details.activeAddress != null &&
            address?.port == details.activeAddress?.port
        ) {
            details.httpsPort
        } else {
            0
        }
    }

    // Helper function to poll a PC
    private suspend fun pollComputer(details: ComputerDetails): Boolean {
        info(
            "Starting parallel poll for ${details.name} " +
                    "(${details.localAddress}, ${details.remoteAddress}, " +
                    "${details.manualAddress}, ${details.ipv6Address})"
        )

        val addressesToTry = listOfNotNull(
            details.localAddress,
            details.manualAddress,
            details.remoteAddress,
            details.ipv6Address
        ).distinct()

        if (addressesToTry.isEmpty()) {
            info("No valid addresses to poll for ${details.name}")
            return false
        }

        val deferredPolls = addressesToTry.map { address ->
            applicationScope.async {
                address to tryPollIp(details, address)
            }
        }

        // This variable will hold the first successful result.
        var successfulResult: Pair<AddressTuple, ComputerDetails>? = null

        // Use a standard loop instead of select to await results.
        // This gives us more control over handling failures.
        for (deferred in deferredPolls) {
            try {
                val (address, returnedDetails) = deferred.await()
                if (returnedDetails != null) {
                    // SUCCESS! We found a working address.
                    successfulResult = address to returnedDetails

                    // We have our winner. We can stop waiting for other polls.
                    break
                }
                // If returnedDetails is null, we simply continue to the next deferred poll.
            } catch (e: Exception) {
                // This catches exceptions from within tryPollIp or the async block itself.
                // It's a failed poll, so we just continue to the next one.
                info(
                    "A poll for ${details.name} failed with an exception: ${e.message}"
                )
            }
        }

        // Crucial: Cancel any remaining, unfinished polls to save resources.
        deferredPolls.forEach { it.cancel() }

        // Process the final result.
        return if (successfulResult != null) {
            val (activeAddress, polledDetails) = successfulResult
            info(
                "Parallel poll for ${details.name} SUCCEEDED at address: $activeAddress"
            )

            details.updateComputerDetails(polledDetails)
            details.activeAddress = activeAddress
            true
        } else {
            info("Parallel poll for ${details.name} FAILED on all addresses.")
            false
        }
    }

    override fun onCreate() {

        // Bind to the discovery service
        isDiscoveryServiceBound = bindService(
            Intent(this, DiscoveryService::class.java),
            discoveryServiceConnection, BIND_AUTO_CREATE
        )

        // Lookup or generate this device's UID
        idManager = UniqueIdManager(this)

        // Initialize the DB
        dbManager = getInstance(this)

        for (computer in dbManager!!.allComputers) {
            // Add tuples for each computer
            applicationScope.launch {
                addTuple(computer)
            }
        }

        // Monitor for network changes to invalidate our PC state
        networkCallback = object : NetworkCallback() {
            override fun onAvailable(network: Network) {
                info("Resetting PC state for new available network")
                applicationScope.launch {
                    updateComputerStates(ComputerDetails.State.UNKNOWN)
                }
            }

            override fun onLost(network: Network) {
                info("Offlining PCs due to network loss")
                applicationScope.launch {
                    updateComputerStates(ComputerDetails.State.OFFLINE)
                }
            }

            private suspend fun updateComputerStates(newState: ComputerDetails.State) {
                mutex.withLock {
                    pollingTuples.forEach { tuple ->
                        tuple.computer.state = newState
                        listener?.notifyComputerUpdated(tuple.computer)
                    }
                }
            }
        }
        val connMgr = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connMgr.registerDefaultNetworkCallback(networkCallback)
    }

    // Release closable resources
    override fun onDestroy() {

        // Unregister the network callback
        val connMgr = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connMgr.unregisterNetworkCallback(networkCallback)

        // Unbind from the discovery service
        if (isDiscoveryServiceBound) {
            unbindService(discoveryServiceConnection)
            isDiscoveryServiceBound = false
        }

        // Close the DB if it's open
        if (dbManager != null) {
            dbManager!!.close()
        }
    }

    // Return the binder
    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    // Inner class to encapsulate applist polling
    @Stable
    inner class ApplistPoller(private val computer: ComputerDetails) {
        // A Job to control the lifecycle of the polling coroutine
        private var pollingJob: Job? = null

        // A channel to trigger an immediate poll. It's like a queue that holds "poll now" requests.
        // We use a RENDEZVOUS channel which holds at most one item until a receiver is ready.
        private val pollNowChannel = Channel<Unit>(Channel.RENDEZVOUS)

        /**
         * Triggers an immediate poll for the app list, in addition to the regular periodic polls.
         * This is non-blocking and safe to call from any thread.
         */
        fun pollNow() {
            // 'trySend' is non-blocking. If the channel is full (a poll is already requested),
            // it does nothing, which is fine.
            pollNowChannel.trySend(Unit)
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        fun start() {
            // Only start a new job if there isn't an active one.
            if (pollingJob?.isActive == true) {
                return
            }

            pollingJob = applicationScope.launch {
                info("Starting app list polling for ${computer.name}")
                var receivedAppList = false
                var emptyAppListResponses = 0

                // This is the main polling loop. It continues as long as the job is active.
                while (isActive) {
                    // Pre-condition checks
                    if (computer.state != ComputerDetails.State.ONLINE ||
                        computer.pairState != PairingManager.PairState.PAIRED ||
                        computer.uuid == null)
                    {
                        // If conditions aren't met, wait a bit before checking again.
                        delay(APPLIST_FAILED_POLLING_RETRY_MS.milliseconds)
                        continue // Skip to the next loop iteration
                    }

                    // --- Main polling logic ---
                    try {
                        val tuple = getPollingTuple(computer)

                        // Use withContext to ensure network I/O is on the correct dispatcher.
                        val appList = withContext(Dispatchers.IO) {
                            val http = NvHTTP(
                                getCurrentAddressFromComputer(computer),
                                computer.httpsPort,
                                idManager!!.uniqueId,
                                computer.serverCert,
                                PlatformBinding.getCryptoProvider(this@ComputerManagerService)
                            )
                            getString(tuple, http)
                        }

                        if (appList.isEmpty()) {
                            warning("Null or empty app list received from ${computer.uuid}")
                        } else {
                            val list: List<NvApp> = NvHTTP.getAppListByReader(StringReader(appList))

                            if (list.isNotEmpty()) {
                                emptyAppListResponses = 0
                            } else {
                                warning("Empty app list JSON received from ${computer.uuid}")
                                emptyAppListResponses++
                            }

                            if (list.isNotEmpty() || emptyAppListResponses >= EMPTY_LIST_THRESHOLD) {
                                // Success case
                                receivedAppList = true
                                computer.rawAppList = appList
                                listener?.notifyComputerUpdated(computer)

                                // Save to cache file
                                try {
                                    openCacheFileForOutput(cacheDir, "applist", computer.uuid)
                                        .use { writeStringToOutputStream(it, appList) }
                                } catch (e: IOException) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                    } catch (e: XmlPullParserException) {
                        e.printStackTrace()
                    }

                    // --- Non-blocking wait for the next poll ---
                    val delayMs = if (receivedAppList) {
                        APPLIST_POLLING_PERIOD_MS
                    } else {
                        APPLIST_FAILED_POLLING_RETRY_MS
                    }

                    // select expression waits for EITHER the timer to run out OR a "poll now" signal.
                    select {
                        // Wait for the calculated delay
                        this.onTimeout(delayMs.milliseconds)  { /* Do nothing */ }
                        // OR wait for a signal from pollNow()
                        pollNowChannel.onReceive { /* Do nothing */ }
                    }
                }
            }
        }

        fun stop() {
            // Cancel the job and wait for it to complete.
            pollingJob?.cancel()
            pollingJob = null
        }

        // Helper function to get a polling tuple
        private suspend fun getPollingTuple(details: ComputerDetails): PollingTuple? {
            // withLock is fine for a quick, non-blocking read.
            return mutex.withLock {
                pollingTuples.find { it.computer.uuid == details.uuid }
            }
        }
    }

    // Helper function to check if the device is connected to a VPN
    private fun isActiveNetworkVpn(context: Context): Boolean {
        val connMgr =
            context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connMgr.activeNetwork ?: return false

        val netCaps = connMgr.getNetworkCapabilities(activeNetwork) ?: return false

        return netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !netCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    companion object {
        private const val SERVERINFO_POLLING_PERIOD_MS = 2000L
        private const val APPLIST_POLLING_PERIOD_MS = 35000L
        private const val APPLIST_FAILED_POLLING_RETRY_MS = 2500L
        private const val MDNS_QUERY_PERIOD_MS = 2000
        private const val OFFLINE_POLL_TRIES = 3
        private const val INITIAL_POLL_TRIES = 2
        private const val EMPTY_LIST_THRESHOLD = 3
        private const val POLL_DATA_TTL_MS = 35000L

        @Throws(IOException::class)
        private suspend fun getString(tuple: PollingTuple?, http: NvHTTP): String {
            var appList: String
            if (tuple != null) {
                tuple.networkLock.withLock { appList = http.appListRaw() }
            } else {
                // No polling is happening now, so we just call it directly
                appList = http.appListRaw()
            }
            return appList
        }

        var dbManager: ComputerDatabaseManager? = null
    }
}

@Stable
internal class PollingTuple(val computer: ComputerDetails, var job: Job?) {
    val networkLock: Mutex = Mutex()
    var lastSuccessfulPollMs: Long = 0

}
