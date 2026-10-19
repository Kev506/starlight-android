package com.starlight.binding.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.Toast
import com.starlight.R
import com.starlight.binding.audio.AudioOutputDiagnostics.FailureReason
import com.starlight.nvstream.av.audio.AudioRenderer
import com.starlight.nvstream.jni.StarBridge.AudioConfiguration
import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.severe
import com.starlight.utils.LimeLog.warning
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds

/**
 * With thanks to BuffMcBigHuge
 * Created by https://github.com/BuffMcBigHuge
 * Refactored and updated by Kevin Andrews on 05/10/2026.
 * Added ENCODED <-> AudioTrack hot swap for bluetooth devices for earlier API's.
 * Refactored audio thread worker loop to use Coroutines and Jobs.
 */

class Ac3AudioRenderer(
    context: Context, private val requestedMode: AudioOutputMode,
    pcmAudioFxCompatibilityValue: Boolean, private val disableWarnings: Boolean
) : AudioRenderer {

    private enum class State {
        NEW,
        READY_ENCODED,
        READY_PCM_FALLBACK,
        RUNNING_ENCODED,
        FALLING_BACK,
        RUNNING_PCM_FALLBACK,
        STOPPED,
        CLEANED
    }

    private val context: Context = context.applicationContext
    private val audioManager: AudioManager =
        this.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val outputFormat: EncodedAudioFormat = EncodedAudioFormat.fromMode(requestedMode)
    private val capabilities: AndroidAudioCapabilities = AndroidAudioCapabilities(this.context)
    private val accumulator: PcmFrameAccumulator = PcmFrameAccumulator()
    private val encoder: NativeAc3Encoder = NativeAc3Encoder()
    private val encodedSink: EncodedAudioTrackSink = EncodedAudioTrackSink(outputFormat)
    private val diagnostics: AudioOutputDiagnostics = AudioOutputDiagnostics()
    private val overloadMonitor: QueueOverloadMonitor = QueueOverloadMonitor()
    private val routeMonitor: AudioRouteMonitor
    private val pcmFallback: AudioTrackRenderer =
        AudioTrackRenderer(this.context, pcmAudioFxCompatibilityValue)
    private val encodedPacket: ByteBuffer = ByteBuffer
        .allocateDirect(NativeAc3Encoder.OUTPUT_CAPACITY)
        .order(ByteOrder.nativeOrder())
    private val stateLock = ReentrantLock()

    @Volatile
    private var state = State.NEW

    @Volatile
    private var stopRequested = false

    @Volatile
    private var fallbackRequested: FailureReason? =
        FailureReason.NONE

    private var audioExecutor: ExecutorService? = null
    private var audioDispatcher: ExecutorCoroutineDispatcher? = null
    private var audioScope: CoroutineScope? = null
    private var workerJob: Job? = null

    private var inputConfiguration: AudioConfiguration? = null
    private var inputSampleRate = 0
    private var inputSamplesPerFrame = 0
    private var streamStarted = false
    private var pcmSetupSucceeded = false
    private var deviceCallbackRegistered = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            for (device in addedDevices) {
                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET
                ) {

                    handleDeviceChange()

                    return
                }
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            for (device in removedDevices) {
                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET
                ) {

                    handleDeviceChange()

                    return
                }
            }
        }
    }

    init {
        routeMonitor = AudioRouteMonitor {
            requestFallback(FailureReason.ROUTE_CHANGED)
        }
    }

    private fun createAudioScope(): CoroutineScope {
        cancelAudioScope()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "StarlightEncodedAudio").apply {
                priority = Thread.MAX_PRIORITY
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            }
        }

        audioExecutor = executor
        val dispatcher = executor.asCoroutineDispatcher()
        audioDispatcher = dispatcher
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        audioScope = scope
        return scope
    }

    private fun cancelAudioScope() {
        audioScope?.cancel()
        audioScope = null
        audioDispatcher?.close()
        audioDispatcher = null
        audioExecutor?.shutdown()
        audioExecutor = null
    }

    private fun stopWorkerJob() {
        accumulator.stop()
        val job = workerJob
        workerJob = null
        if (job != null && job.isActive) {
            job.cancel()
            applicationScope.launch(Dispatchers.Main) {
                withTimeoutOrNull(1000.milliseconds) {
                    job.join()
                }
            }
        }
        cancelAudioScope()
    }

    private fun registerDeviceCallback() {
        if (!deviceCallbackRegistered) {
            audioManager.registerAudioDeviceCallback(
                deviceCallback,
                Handler(Looper.getMainLooper())
            )
            deviceCallbackRegistered = true
        }
    }

    private fun unregisterDeviceCallback() {
        if (deviceCallbackRegistered) {
            try {
                audioManager.unregisterAudioDeviceCallback(deviceCallback)
            } catch (_: Exception) {
            }
            deviceCallbackRegistered = false
        }
    }

    @Synchronized
    private fun handleDeviceChange() {
        stateLock.withLock {
            if (stopRequested || state == State.STOPPED || state == State.CLEANED) {
                return
            }
            val current = state
            if (current == State.RUNNING_PCM_FALLBACK || current == State.READY_PCM_FALLBACK) {
                val capability: AndroidAudioCapabilities.Result = capabilities.probeEncodedRoute(
                    EncodedAudioTrackSink.buildFormat(outputFormat),
                    EncodedAudioTrackSink.buildAttributes(), outputFormat.androidEncoding
                )
                if (capability.reportedSupported) {
                    info(outputFormat.displayName + " route available; hot-swapping from PCM fallback to encoded output")
                    switchToEncoded()
                }
            } else if (current == State.RUNNING_ENCODED || current == State.READY_ENCODED) {
                val capability: AndroidAudioCapabilities.Result = capabilities.probeEncodedRoute(
                    EncodedAudioTrackSink.buildFormat(outputFormat),
                    EncodedAudioTrackSink.buildAttributes(), outputFormat.androidEncoding
                )
                if (!capability.reportedSupported) {
                    info(outputFormat.displayName + " route no longer supported; triggering fallback to PCM")
                    requestFallback(FailureReason.ROUTE_UNSUPPORTED)
                }
            }
        }
    }

    @Synchronized
    private fun switchToEncoded(): Boolean {
        stateLock.withLock {
            if (state != State.RUNNING_PCM_FALLBACK && state != State.READY_PCM_FALLBACK) {
                return false
            }
            if (stopRequested) {
                return false
            }

            val wasRunning = (state == State.RUNNING_PCM_FALLBACK)
            if (wasRunning) {
                pcmFallback.stop()
            }
            pcmFallback.cleanup()
            pcmSetupSucceeded = false

            stopWorkerJob()

            if (!encoder.create(outputFormat)) {
                warning(outputFormat.displayName + " encoder init failed during hot swap; remaining on PCM")
                setupPcmFallback(FailureReason.ENCODER_INIT_FAILED)
                if (wasRunning && streamStarted) {
                    pcmFallback.start()
                    state = State.RUNNING_PCM_FALLBACK
                }
                return false
            }

            if (!encodedSink.createAndValidate()) {
                warning(outputFormat.displayName + " sink init failed during hot swap; remaining on PCM")
                encoder.destroy()
                setupPcmFallback(FailureReason.TRACK_INIT_FAILED)
                if (wasRunning && streamStarted) {
                    pcmFallback.start()
                    state = State.RUNNING_PCM_FALLBACK
                }
                return false
            }

            fallbackRequested = FailureReason.NONE
            overloadMonitor.reset()
            accumulator.start()

            if (wasRunning && streamStarted) {
                if (!encodedSink.start()) {
                    warning(outputFormat.displayName + " sink start failed during hot swap; remaining on PCM")
                    accumulator.stop()
                    encodedSink.release()
                    encoder.destroy()
                    setupPcmFallback(FailureReason.TRACK_INIT_FAILED)
                    pcmFallback.start()
                    state = State.RUNNING_PCM_FALLBACK
                    return false
                }

                state = State.RUNNING_ENCODED
                routeMonitor.register(encodedSink.getTrack()!!)
                val scope = createAudioScope()
                workerJob = scope.launch { workerLoop() }
                info(outputFormat.displayName + " hot swap back to encoded route succeeded: " + encodedSink.describeRoute())
                applicationScope.launch {
                    showSwitchToEncodedMessage()
                }
            } else {
                state = State.READY_ENCODED
                info(outputFormat.displayName + " hot swap back to encoded route ready: " + encodedSink.describeRoute())
            }
            return true
        }
    }

    @Synchronized
    override fun setup(
        sampleRate: Int,
        channels: AudioConfiguration,
        samplesPerFrame: Int
    ): Int {
        stateLock.withLock {
            if (state != State.NEW) {
                return -1
            }
            stopRequested = false
            inputConfiguration = channels
            inputSampleRate = sampleRate
            inputSamplesPerFrame = samplesPerFrame
            fallbackRequested = FailureReason.NONE
            overloadMonitor.reset()

            info(
                "Audio output requested=" + requestedMode +
                        " input=" + channels.channelCount + "ch@" + sampleRate +
                        " callbackSamples=" + samplesPerFrame
            )

            if (channels.channelCount != PcmFrameAccumulator.CHANNELS ||
                sampleRate != 48000
            ) {
                return setupPcmFallback(FailureReason.UNSUPPORTED_FORMAT)
            }

            val capability: AndroidAudioCapabilities.Result = capabilities.probeEncodedRoute(
                EncodedAudioTrackSink.buildFormat(outputFormat),
                EncodedAudioTrackSink.buildAttributes(), outputFormat.androidEncoding
            )
            info(outputFormat.displayName + " capability: " + capability.evidence)
            if (requestedMode.isAutomatic() && !capability.reportedSupported) {
                return setupPcmFallback(FailureReason.ROUTE_UNSUPPORTED)
            }

            if (!encoder.create(outputFormat)) {
                return setupPcmFallback(FailureReason.ENCODER_INIT_FAILED)
            }
            if (!encodedSink.createAndValidate()) {
                encoder.destroy()
                return setupPcmFallback(FailureReason.TRACK_INIT_FAILED)
            }

            info(
                outputFormat.displayName + " effective mode=" + requestedMode + " encoder=" +
                        NativeAc3Encoder.getBuildInfo() + " bitrate=" +
                        outputFormat.bitrate +
                        " channelMap=FL,FR,FC,LFE,BL,BR->FL,FR,FC,LFE,BL,BR"
            )
            state = State.READY_ENCODED
            return 0
        }
    }

    private fun setupPcmFallback(reason: FailureReason?): Int {
        diagnostics.recordFailure(reason)
        val result: Int =
            pcmFallback.setup(inputSampleRate, inputConfiguration!!, inputSamplesPerFrame)
        pcmSetupSucceeded = result == 0
        state = if (pcmSetupSucceeded) State.READY_PCM_FALLBACK else State.STOPPED
        warning(
            outputFormat.displayName +
                    " unavailable during setup; effective mode=PCM reason=" + reason
        )
        if (pcmSetupSucceeded) {
            applicationScope.launch {
                showFallbackMessage()
            }
        }
        return result
    }

    @Synchronized
    override fun start() {
        stateLock.withLock {
            if (streamStarted || stopRequested) {
                return
            }
            if (state != State.READY_PCM_FALLBACK && state != State.READY_ENCODED) {
                return
            }
            registerDeviceCallback()
            streamStarted = true
            if (state == State.READY_PCM_FALLBACK) {
                pcmFallback.start()
                state = State.RUNNING_PCM_FALLBACK
                return
            }
            if (!encodedSink.start()) {
                performFallback(FailureReason.TRACK_INIT_FAILED)
                return
            }
            accumulator.start()
            state = State.RUNNING_ENCODED
            routeMonitor.register(encodedSink.getTrack()!!)
            val scope = createAudioScope()
            workerJob = scope.launch { workerLoop() }
            info(outputFormat.displayName + " track started: " + encodedSink.describeRoute())
        }
    }

    override fun playPcm(data: ShortArray?) {
        val current: State = state
        if (current == State.RUNNING_PCM_FALLBACK) {
            pcmFallback.playPcm(data)
            return
        }
        if (current != State.RUNNING_ENCODED) {
            return
        }

        val startNanos = System.nanoTime()
        val dropped: Int = accumulator.offer(data)
        if (dropped > 12) {
            diagnostics.recordDroppedFrames(dropped)
            val sustainedOverload: Boolean = overloadMonitor.recordDrops(dropped, System.nanoTime())
            warning(
                outputFormat.displayName + " queue overflow: dropped=" + dropped +
                        " windowDrops=" + overloadMonitor.windowDrops +
                        "/" + QueueOverloadMonitor.DROP_THRESHOLD
            )
            if (sustainedOverload) {
                requestFallback(FailureReason.QUEUE_OVERFLOW_LIMIT)
            }
        }
        diagnostics.recordCallback(System.nanoTime() - startNanos)
    }

    private suspend fun workerLoop() {
        var terminalReason: FailureReason? = FailureReason.NONE

        while (currentCoroutineContext().isActive) {
            if (fallbackRequested !== FailureReason.NONE) {
                terminalReason = fallbackRequested
                break
            }

            var frame: PcmFrameAccumulator.Frame? = null
            try {
                frame = accumulator.take()

                if (!currentCoroutineContext().isActive || frame == null) {
                    if (fallbackRequested !== FailureReason.NONE) {
                        terminalReason = fallbackRequested
                    }
                    break
                }

                val encodeStart = System.nanoTime()
                val encodedBytes: Int = encoder.encode(frame.samples, encodedPacket)
                val encodeElapsed = System.nanoTime() - encodeStart

                if (encodedBytes != outputFormat.frameBytes ||
                    (encodedPacket.get(0).toInt() and 0xFF) != 0x0B ||
                    (encodedPacket.get(1).toInt() and 0xFF) != 0x77
                ) {
                    terminalReason = FailureReason.ENCODE_FAILED
                    break
                }

                if (!currentCoroutineContext().isActive) break

                val writeStart = System.nanoTime()
                val written: Int = encodedSink.writeCompleteAccessUnit(encodedPacket, encodedBytes)
                val writeElapsed = System.nanoTime() - writeStart

                if (written != encodedBytes) {
                    val capability = capabilities.probeEncodedRoute(
                        EncodedAudioTrackSink.buildFormat(outputFormat),
                        EncodedAudioTrackSink.buildAttributes(),
                        outputFormat.androidEncoding
                    )
                    terminalReason =
                        if (!capability.evidence!!.contains("headphonesOrBtConnected")) {
                            FailureReason.DEVICE_CONNECTED
                        } else {
                            FailureReason.WRITE_FAILED
                        }
                    break
                }
                diagnostics.recordFrame(encodeElapsed, writeElapsed, accumulator.queueDepth)

            } catch (_: InterruptedException) {
                if (fallbackRequested !== FailureReason.NONE) {
                    terminalReason = fallbackRequested
                }
                break
            } catch (_: CancellationException) {
                if (fallbackRequested !== FailureReason.NONE) {
                    terminalReason = fallbackRequested
                }
                break
            } catch (error: RuntimeException) {
                warning(outputFormat.displayName + " worker exception: " + error)
                terminalReason = FailureReason.INTERNAL_STATE_ERROR
                break
            } finally {
                if (frame != null) {
                    accumulator.release(frame)
                }
            }
        }

        applicationScope.launch(Dispatchers.Main) {
            performFailure(terminalReason)
        }
    }

    @Synchronized
    private fun performFailure(reason: FailureReason?) {
        stateLock.withLock {
            if (reason !== FailureReason.NONE && state == State.RUNNING_ENCODED) {
                performFallback(reason)
            }
        }
    }

    private fun requestFallback(reason: FailureReason?) {
        if (state != State.RUNNING_ENCODED ||
            fallbackRequested !== FailureReason.NONE
        ) {
            return
        }
        fallbackRequested = reason
        accumulator.stop()
        workerJob?.cancel()
    }

    @Synchronized
    private fun performFallback(reason: FailureReason?) {
        stateLock.withLock {
            if (state == State.RUNNING_PCM_FALLBACK || state == State.FALLING_BACK || state == State.STOPPED || state == State.CLEANED || stopRequested) {
                return
            }
            state = State.FALLING_BACK
            diagnostics.recordFailure(reason)
            stopWorkerJob()
            routeMonitor.unregister()
            encodedSink.release()
            encoder.destroy()

            if (stopRequested) {
                state = State.STOPPED
                return
            }

            val result: Int =
                pcmFallback.setup(inputSampleRate, inputConfiguration!!, inputSamplesPerFrame)
            pcmSetupSucceeded = result == 0
            if (pcmSetupSucceeded) {
                if (streamStarted && !stopRequested) {
                    pcmFallback.start()
                    state = State.RUNNING_PCM_FALLBACK
                } else {
                    state = State.READY_PCM_FALLBACK
                }
                if (reason == FailureReason.DEVICE_CONNECTED) {
                    info(
                        outputFormat.displayName +
                                " runtime fallback complete; effective mode=PCM reason=$reason"
                    )
                } else {
                    warning(
                        outputFormat.displayName +
                                " runtime fallback complete; effective mode=PCM reason=$reason"
                    )
                }
                applicationScope.launch {
                    showFallbackMessage()
                }
            } else {
                state = State.STOPPED
                severe(
                    outputFormat.displayName +
                            " runtime fallback failed to initialize PCM: " + result
                )
            }
        }
    }

    private suspend fun showFallbackMessage() {
        if (disableWarnings) {
            return
        }
        delay(1000.milliseconds)
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                context.getString(R.string.encoded_audio_fallback_to_pcm),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private suspend fun showSwitchToEncodedMessage() {
        if (disableWarnings) {
            return
        }
        delay(1000.milliseconds)
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                context.getString(R.string.switch_to_encoded),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Synchronized
    override fun stop() {
        stopRequested = true
        unregisterDeviceCallback()
        stateLock.withLock {
            val current: State = state
            if (current == State.CLEANED || current == State.STOPPED) {
                streamStarted = false
                return
            }
            if (current == State.READY_PCM_FALLBACK || current == State.RUNNING_PCM_FALLBACK) {
                if (current == State.RUNNING_PCM_FALLBACK && streamStarted && pcmSetupSucceeded) {
                    pcmFallback.stop()
                }
                streamStarted = false
                state = State.STOPPED
                return
            }

            routeMonitor.unregister()
            streamStarted = false
            state = State.STOPPED
        }

        stopWorkerJob()
        encodedSink.stopAndFlush()
    }

    @Synchronized
    override fun cleanup() {
        stop()
        stateLock.withLock {
            if (state == State.CLEANED) {
                return
            }
            unregisterDeviceCallback()
            routeMonitor.unregister()
            accumulator.stop()
            encodedSink.release()
            stopWorkerJob()
            encoder.destroy()
            if (pcmSetupSucceeded) {
                pcmFallback.cleanup()
                pcmSetupSucceeded = false
            }
            state = State.CLEANED
        }
    }
}