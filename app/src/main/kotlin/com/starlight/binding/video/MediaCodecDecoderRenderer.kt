package com.starlight.binding.video

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.VideoCapabilities
import android.media.MediaCodecInfo.VideoCapabilities.PerformancePoint
import android.media.MediaFormat
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import androidx.activity.ComponentActivity
import androidx.annotation.IntDef
import androidx.annotation.RequiresApi
import androidx.collection.LongSparseArray
import androidx.core.hardware.display.DisplayManagerCompat
import com.starlight.R
import com.starlight.models.MainViewModel
import com.starlight.nvstream.av.video.VideoDecoderRenderer
import com.starlight.nvstream.jni.StarBridge
import com.starlight.nvstream.jni.StarBridge.CAPABILITY_SLICES_PER_FRAME
import com.starlight.nvstream.jni.StarBridge.getEstimatedRttInfo
import com.starlight.preferences.DataStoreConfiguration
import com.starlight.preferences.FormatOption
import com.starlight.utils.Constants.FRAME_PACING_BALANCED
import com.starlight.utils.Constants.FRAME_PACING_CAP_FPS
import com.starlight.utils.Constants.FRAME_PACING_MAX_SMOOTHNESS
import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.severe
import com.starlight.utils.LimeLog.warning
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import org.jcodec.codecs.h264.H264Utils
import org.jcodec.codecs.h264.io.model.SeqParameterSet
import org.jcodec.codecs.h264.io.model.VUIParameters
import org.jcodec.codecs.h264.io.model.VUIParameters.BitstreamRestriction
import java.io.IOException
import java.io.Serial
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
@Suppress("unused")
class MediaCodecDecoderRenderer(

    private val activity: ComponentActivity, private val prefs: DataStoreConfiguration,
    private val crashListener: CrashListener,
    private val consecutiveCrashCount: Int, meteredData: Boolean, requestedHdr:
    Boolean, private val glRenderer: String,
    private val perfListener: PerfOverlayListener,
    private val viewModel: MainViewModel
) :
    VideoDecoderRenderer, Choreographer.FrameCallback {

    private val avcDecoder: MediaCodecInfo?
    private val hevcDecoder: MediaCodecInfo?
    private val av1Decoder: MediaCodecInfo?

    private val vpsBuffers = ArrayList<ByteArray>()
    private val spsBuffers = ArrayList<ByteArray>()
    private val ppsBuffers = ArrayList<ByteArray>()

    private var currentHdrMetadata: ByteArray? = null
    private var nextInputBuffer: ByteBuffer? = null

    private var videoDecoder: MediaCodec? = null
    private lateinit var renderTarget: SurfaceHolder

    private var submittedCsd = false
    private var adaptivePlayback = false
    private var directSubmit = false
    private var fusedIdrFrame = false
    private var constrainedHighProfile = false
    private var refFrameInvalidationAvc = false
    private var refFrameInvalidationHevc = false
    private var refFrameInvalidationAv1 = false
    private var refFrameInvalidationActive = false
    private var csdSubmittedForThisFrame = false

    private val context: Context = activity
    private val optimalSlicesPerFrame: Byte

    private var nextInputBufferIndex = -1
    private var initialWidth = 0
    private var initialHeight = 0
    private var baseTimestampUs: Long = 0
    var activeVideoFormat = 0

    @Volatile
    private var stopping = false
    private var needsBaselineSpsHack = false
    private var needsSpsBitstreamFixup = false
    private var reportedCrash = false
    private var foreground = true

    private var inputFormat: MediaFormat? = null
    private var outputFormat: MediaFormat? = null
    private var configuredFormat: MediaFormat? = null
    private var savedSps: SeqParameterSet? = null
    private var initialException: RendererException? = null
    private var choreographerJob: Job? = null
    private var choreographer: Choreographer? = null

    private val activeWindowVideoStats: VideoStats = VideoStats()
    private val lastWindowVideoStats: VideoStats = VideoStats()
    private val globalVideoStats: VideoStats = VideoStats()

    private var initialExceptionTimestamp: Long = 0
    private var codecRecoveryThreadQuiescedFlags = 0
    private var codecRecoveryAttempts = 0
    private var lastTimestampUs: Long = 0
    private var lastFrameNumber = 0
    private var refreshRate = 0
    private var lastRenderedFrameTimeNanos: Long = 0
    private var numSpsIn = 0
    private var numPpsIn = 0
    private var numVpsIn = 0
    private var numFramesIn = 0
    private var numFramesOut = 0

    private var glesBridge: GlesPassthroughBridge? = null
    private val outputBufferQueue = LinkedBlockingQueue<Int>()
    private val renderChannel = Channel<Long>(Channel.CONFLATED)
    private var renderJob: Job? = null
    private var startRenderingJob: Job? = null
    private val codecRecoveryType = AtomicInteger(CR_RECOVERY_TYPE_NONE)
    private val codecRecoveryMonitor = Channel<Unit>(Channel.RENDEZVOUS)
    private lateinit var choreographerDispatcher: CloseableCoroutineDispatcher
    private val lock = ReentrantLock()

    // Decode latency tracking: map PTS(us) -> enqueue time (ns)
    private val enqueueNsByPtsUs: LongSparseArray<Long?> = LongSparseArray()

    @IntDef(
        CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART,
        CR_RECOVERY_TYPE_RESET
    )
    private annotation class CodecRecoveryType

    // Find the first available AVC decoder
    private fun findAvcDecoder(): MediaCodecInfo? {
        return MediaCodecHelper.findProbableSafeDecoder(
            "video/avc",
            CodecProfileLevel.AVCProfileHigh
        )
            ?: MediaCodecHelper.findFirstDecoder("video/avc")
    }

    // Check if the decoder supports performance points
    private fun decoderCanMeetPerformancePoint(
        caps: VideoCapabilities,
        prefs: DataStoreConfiguration
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val targetPerfPoint = PerformancePoint(prefs.width, prefs.height, prefs.fps)
            caps.supportedPerformancePoints?.any { it.covers(targetPerfPoint) }?.let {
                return it
            }
            // No suitable performance point found for Android Q+
            return false
        }

        // For older Android versions, fall back to achievable frame rates
        try {
            caps.getAchievableFrameRatesFor(prefs.width, prefs.height)?.let { fpsRange ->
                return prefs.fps <= fpsRange.upper
            }
        } catch (_: IllegalArgumentException) {
            // Video size not supported
            return false
        }

        // Last resort: check basic size and rate support
        return caps.areSizeAndRateSupported(prefs.width, prefs.height, prefs.fps.toDouble())
    }

    // Check if the decoder supports performance points HEVC and not AVC
    private fun decoderCanMeetPerformancePointWithHevcAndNotAvc(
        hevcDecoderInfo: MediaCodecInfo,
        avcDecoderInfo: MediaCodecInfo,
        prefs: DataStoreConfiguration
    ): Boolean {
        val avcCapabilities = avcDecoderInfo.getCapabilitiesForType("video/avc")?.videoCapabilities
        val hevcCapabilities =
            hevcDecoderInfo.getCapabilitiesForType("video/hevc")?.videoCapabilities
        val canAvcMeetPerformance = decoderCanMeetPerformancePoint(avcCapabilities!!, prefs)
        val canHevcMeetPerformance = decoderCanMeetPerformancePoint(hevcCapabilities!!, prefs)

        return canHevcMeetPerformance && !canAvcMeetPerformance
    }

    // Check if the decoder supports performance points AV1 and not HEVC
    private fun decoderCanMeetPerformancePointWithAv1AndNotHevc(
        av1DecoderInfo: MediaCodecInfo,
        hevcDecoderInfo: MediaCodecInfo, prefs: DataStoreConfiguration
    ): Boolean {
        val av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01")?.videoCapabilities
        val hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc")?.videoCapabilities
        val canHevcMeetPerformance = decoderCanMeetPerformancePoint(hevcCaps!!, prefs)
        val canAv1MeetPerformance = decoderCanMeetPerformancePoint(av1Caps!!, prefs)

        return canAv1MeetPerformance && !canHevcMeetPerformance
    }

    // Check if the decoder supports performance points AV1 and not AVC
    private fun decoderCanMeetPerformancePointWithAv1AndNotAvc(
        av1DecoderInfo: MediaCodecInfo,
        avcDecoderInfo: MediaCodecInfo?, prefs: DataStoreConfiguration
    ): Boolean {
        val avcCaps = avcDecoderInfo!!.getCapabilitiesForType("video/avc")?.videoCapabilities
        val av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01")?.videoCapabilities
        val canAvcMeetPerformance = decoderCanMeetPerformancePoint(avcCaps!!, prefs)
        val canAv1MeetPerformance = decoderCanMeetPerformancePoint(av1Caps!!, prefs)

        return canAv1MeetPerformance && !canAvcMeetPerformance
    }

    // Find the first available HEVC decoder
    private fun findHevcDecoder(
        prefs: DataStoreConfiguration,
        meteredNetwork: Boolean, requestedHdr: Boolean
    ): MediaCodecInfo? {
        // Early exit if H.264 is forced
        if (prefs.videoFormat == FormatOption.FORCE_H264) {
            return null
        }

        val hevcDecoderInfo =
            MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1) ?: return null

        // Handle non-whitelisted HEVC decoders
        if (!MediaCodecHelper.decoderIsWhitelistedForHevc(hevcDecoderInfo)) {
            info(
                "Found HEVC decoder ${hevcDecoderInfo.name}, but it's not whitelisted"
            )

            // Force HEVC under specific conditions
            val forceHevc = when {
                prefs.videoFormat == FormatOption.FORCE_HEVC -> {
                    info("Forcing HEVC due to user preference")
                    true
                }

                requestedHdr -> {
                    info("Forcing HEVC for HDR streaming")
                    true
                }

                prefs.width > 4096 || prefs.height > 4096 -> {
                    info("Forcing HEVC for over 4K streaming")
                    true
                }

                avcDecoder != null && decoderCanMeetPerformancePointWithHevcAndNotAvc(
                    hevcDecoderInfo, avcDecoder, prefs
                ) -> {
                    info(
                        "Using non-whitelisted HEVC decoder to meet performance point"
                    )
                    true
                }

                else -> false
            }

            if (!forceHevc) return null
        }

        return hevcDecoderInfo
    }

    // Find the first available AV1 decoder
    private fun findAv1Decoder(prefs: DataStoreConfiguration): MediaCodecInfo? {

        val decoderInfo = MediaCodecHelper.findProbableSafeDecoder("video/av01", -1) ?: return null

        if (!MediaCodecHelper.isDecoderWhitelistedForAv1(decoderInfo)) {
            info("Found AV1 decoder ${decoderInfo.name}, but it's not whitelisted")

            when {
                prefs.videoFormat == FormatOption.FORCE_AV1 -> {
                    info("Forcing AV1 enabled despite non-whitelisted decoder")
                }

                hevcDecoder != null && decoderCanMeetPerformancePointWithAv1AndNotHevc(
                    decoderInfo,
                    hevcDecoder,
                    prefs
                ) -> {
                    info(
                        "Using non-whitelisted AV1 decoder to meet performance point (over HEVC)"
                    )
                }

                hevcDecoder == null && decoderCanMeetPerformancePointWithAv1AndNotAvc(
                    decoderInfo,
                    avcDecoder,
                    prefs
                ) -> {
                    info(
                        "Using non-whitelisted AV1 decoder to meet performance point (over AVC)"
                    )
                }

                else -> return null
            }
        }

        return decoderInfo
    }

    // Set the SurfaceHolder for rendering
    fun setRenderTarget(renderTarget: SurfaceHolder) {
        this.renderTarget = renderTarget
    }

    init {
        if (prefs.framePacing == FRAME_PACING_BALANCED) {
            choreographerDispatcher = newSingleThreadContext("Choreographer-Thread")
            launchChoreographer()
            info("Starting Choreographer job")
        }

        avcDecoder = findAvcDecoder()
        when {
            avcDecoder != null -> {
                info("Selected AVC decoder: " + avcDecoder.name)
            }

            else -> {
                warning("No AVC decoder found")
            }
        }
        hevcDecoder = findHevcDecoder(prefs, meteredData, requestedHdr)
        when {
            hevcDecoder != null -> {
                info("Selected HEVC decoder: " + hevcDecoder.name)
            }

            else -> {
                info("No HEVC decoder found")
            }
        }
        av1Decoder = findAv1Decoder(prefs)
        when {
            av1Decoder != null -> {
                info("Selected AV1 decoder: " + av1Decoder.name)
            }

            else -> {
                info("No AV1 decoder found")
            }
        }

        // Set attributes that are queried in getCapabilities(). This must be done here
        // because getCapabilities() may be called before setup() in current versions of the common
        // library. The limitation of this is that we don't know whether we're using HEVC or AVC.
        var avcOptimalSlicesPerFrame = 0
        var hevcOptimalSlicesPerFrame = 0
        when {
            avcDecoder != null -> {
                directSubmit = MediaCodecHelper.decoderCanDirectSubmit(avcDecoder.name)
                refFrameInvalidationAvc = MediaCodecHelper.decoderSupportsRefFrameInvalidationAvc(
                    avcDecoder)
                avcOptimalSlicesPerFrame =
                    MediaCodecHelper.getDecoderOptimalSlicesPerFrame(avcDecoder.name)
                        .toInt()
                if (directSubmit) {
                    info("Decoder " + avcDecoder.name + " will use direct submit")
                }
                if (refFrameInvalidationAvc) {
                    info(
                        "Decoder " + avcDecoder.name + " will use reference frame invalidation for AVC"
                    )
                }
                info(
                    "Decoder " + avcDecoder.name + " wants " + avcOptimalSlicesPerFrame + " slices per frame"
                )
            }
        }
        when {
            hevcDecoder != null -> {
                refFrameInvalidationHevc =
                    MediaCodecHelper.decoderSupportsRefFrameInvalidationHevc(hevcDecoder)
                hevcOptimalSlicesPerFrame =
                    MediaCodecHelper.getDecoderOptimalSlicesPerFrame(hevcDecoder.name)
                        .toInt()
                if (refFrameInvalidationHevc) {
                    info(
                        "Decoder " + hevcDecoder.name + " will use reference frame invalidation for HEVC"
                    )
                }
                info(
                    "Decoder " + hevcDecoder.name + " wants " + hevcOptimalSlicesPerFrame + " slices per frame"
                )
            }
        }
        when {
            av1Decoder != null -> {
                refFrameInvalidationAv1 =
                    MediaCodecHelper.decoderSupportsRefFrameInvalidationAv1(av1Decoder)
                if (refFrameInvalidationAv1) {
                    info(
                        "Decoder " + av1Decoder.name + " will use reference frame invalidation for AV1"
                    )
                }
            }
        }

        // Use the larger of the two slices per frame preferences
        optimalSlicesPerFrame =
            max(avcOptimalSlicesPerFrame.toDouble(), hevcOptimalSlicesPerFrame.toDouble())
                .toInt().toByte()
        info("Requesting $optimalSlicesPerFrame slices per frame")
        if (consecutiveCrashCount % 2 == 1) {
            refFrameInvalidationHevc = false
            refFrameInvalidationAvc = false
            warning("Disabling RFI due to previous crash")
        }
    }

    // Check if the HEVC decoder is supported
    fun isHevcSupported(): Boolean {
        return hevcDecoder != null
    }

    // Check if the AVC decoder is supported
    fun isAvcSupported(): Boolean {
        return avcDecoder != null
    }

    // Check if the HEVC main 10 HDR10 decoder is supported
    fun isHevcMain10Hdr10Supported(): Boolean {

        if (hevcDecoder == null) return false

        val isSupported = hevcDecoder.getCapabilitiesForType("video/hevc").profileLevels.any {
            it.profile == CodecProfileLevel.HEVCProfileMain10HDR10
        }

        if (isSupported) {
            info("HEVC decoder " + hevcDecoder.name + " supports HEVC Main10 HDR10")
        }

        return isSupported
    }

    // Check if the AV1 decoder is supported
    fun isAv1Supported(): Boolean {
        return av1Decoder != null
    }

    // Check if the AV1 main 10 HDR10 decoder is supported
    @RequiresApi(Build.VERSION_CODES.Q)
    fun isAv1Main10Supported(): Boolean {

        if (av1Decoder == null) return false

        val isSupported = av1Decoder.getCapabilitiesForType("video/av01").profileLevels.any {
            it.profile == CodecProfileLevel.AV1ProfileMain10HDR10
        }

        if (isSupported) {
            info("AV1 decoder " + av1Decoder.name + " supports AV1 Main 10 HDR10")
        }

        return isSupported
    }

    // Get the preferred color space
    fun preferredColorSpace(): Int {
        return StarBridge.COLORSPACE_REC_709
    }

    // Get the preferred color range
    fun preferredColorRange(): Int {
        return if (prefs.fullRange)
            StarBridge.COLOR_RANGE_FULL else StarBridge.COLOR_RANGE_LIMITED
    }

    // Notify the renderer that the video is foregrounded
    fun notifyVideoForeground() {
        foreground = true
    }

    // Notify the renderer that the video is background
    fun notifyVideoBackground() {
        foreground = false
    }

    // Create a MediaFormat for the decoder
    private fun createBaseMediaFormat(mimeType: String): MediaFormat {
        val videoFormat = MediaFormat.createVideoFormat(mimeType, initialWidth, initialHeight)

        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, refreshRate)

        if (adaptivePlayback) {
            videoFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, initialWidth)
            videoFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, initialHeight)
        }

        val colorRange = if (preferredColorRange() == StarBridge.COLOR_RANGE_FULL) {
            MediaFormat.COLOR_RANGE_FULL
        } else {
            MediaFormat.COLOR_RANGE_LIMITED
        }
        videoFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, colorRange)

        // If the stream is HDR-capable, the decoder will detect transitions in color standards
        if (activeVideoFormat and StarBridge.VIDEO_FORMAT_MASK_10BIT == 0) {
            videoFormat.setInteger(
                MediaFormat.KEY_COLOR_TRANSFER,
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            )

            val colorStandard = when (preferredColorSpace()) {
                StarBridge.COLORSPACE_REC_709 -> MediaFormat.COLOR_STANDARD_BT709
                StarBridge.COLORSPACE_REC_2020 -> MediaFormat.COLOR_STANDARD_BT2020
                else -> null // Handle the default case or throw an exception if needed
            }
            colorStandard?.let {
                videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, it)
            }
        }

        return videoFormat
    }

    // Configure the decoder for the given format
    private fun configureAndStartDecoder(format: MediaFormat?) {
        // Set HDR metadata if present
        if (currentHdrMetadata != null) {
            val hdrStaticInfo = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
            val hdrMetadata = ByteBuffer.wrap(currentHdrMetadata!!).order(ByteOrder.LITTLE_ENDIAN)

            // Create an HDMI Dynamic Range and Mastering InfoFrame as defined by CTA-861.3
            hdrStaticInfo.put(0.toByte()) // Metadata type
            hdrStaticInfo.putShort(hdrMetadata.getShort()) // RX
            hdrStaticInfo.putShort(hdrMetadata.getShort()) // RY
            hdrStaticInfo.putShort(hdrMetadata.getShort()) // GX
            hdrStaticInfo.putShort(hdrMetadata.getShort()) // GY
            hdrStaticInfo.putShort(hdrMetadata.getShort()) // BX
            hdrStaticInfo.putShort(hdrMetadata.getShort()) // BY
            hdrStaticInfo.putShort(hdrMetadata.getShort()) // White X
            hdrStaticInfo.putShort(hdrMetadata.getShort()) // White Y
            hdrStaticInfo.putShort(hdrMetadata.getShort()) // Max mastering luminance
            hdrStaticInfo.putShort(hdrMetadata.getShort()) // Min mastering luminance
            hdrStaticInfo.putShort(hdrMetadata.getShort()) // Max content luminance
            hdrStaticInfo.putShort(hdrMetadata.getShort()) // Max frame average luminance

            hdrStaticInfo.rewind()
            format!!.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdrStaticInfo)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            format!!.removeKey(MediaFormat.KEY_HDR_STATIC_INFO)
        }


        info("Configuring with format: $format")

        if (prefs.videoSr) {
            // SGSR1 Upscaling
            glesBridge = GlesPassthroughBridge(this.context)
            glesBridge?.initialize(
                renderTarget.surface,
                initialWidth,
                initialHeight
            )
            val decoderSurface: Surface? = glesBridge?.decoderSurface

            videoDecoder!!.configure(format, decoderSurface, null, 0)

            try {
                applySurfaceFrameRate(decoderSurface, this.refreshRate)
            } catch (ignored: Throwable) { }

            VIDEO_SR_UPSCALE_RATIO =
                renderTarget.surfaceFrame.width() / initialWidth.toFloat()
        } else {
            val renderSurface = renderTarget.surface

            videoDecoder!!.configure(format, renderSurface, null, 0)

            try {
                applySurfaceFrameRate(renderSurface, this.refreshRate)
            } catch (ignored: Throwable) { }
        }

        configuredFormat = format

        // After reconfiguration, we must resubmit CSD buffers
        submittedCsd = false

        // Clear the buffers
        with(mutableListOf(vpsBuffers, spsBuffers, ppsBuffers)) {
            forEach { it.clear() }
        }

        // This will contain the actual accepted input format attributes
        inputFormat = videoDecoder!!.inputFormat
        info("Input format: $inputFormat")
        videoDecoder!!.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)

        // Start the decoder
        videoDecoder!!.start()
    }

    // Apply surface frame rate - API 30+
    private fun applySurfaceFrameRate(surface: Surface?, targetFps: Int) {
        if (surface == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                surface.setFrameRate(
                    targetFps.toFloat(),
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                )
                info("Applied Surface frame rate: $targetFps Hz")
            }
        } catch (t: Throwable) {
            // best-effort
        }
    }

    // Try to configure the decoder with the given format
    private fun tryConfigureDecoder(
        selectedDecoderInfo: MediaCodecInfo?,
        format: MediaFormat,
        throwOnCodecError: Boolean
    ): Boolean {
        var configured = false

        try {
            videoDecoder = MediaCodec.createByCodecName(selectedDecoderInfo!!.name)
            configureAndStartDecoder(format)

            info(
                "Using codec ${selectedDecoderInfo.name} for hardware decoding ${
                    format.getString(
                        MediaFormat.KEY_MIME
                    )
                }"
            )

            configured = true
        } catch (e: Exception) {
            // Handle all exceptions in a single catch block
            warning("Failed to configure decoder: $e")
            if (throwOnCodecError) {
                throw if (e is IOException) RuntimeException(e) else e
            }
        } finally {
            if (!configured && videoDecoder != null) {
                videoDecoder!!.release()
            }
            if (!configured && glesBridge != null) {
                glesBridge!!.release()
                glesBridge = null
            }
        }

        return configured
    }

    // Initialize the decoder
    private fun initializeDecoder(throwOnCodecError: Boolean): Int {

        val (mimeType, selectedDecoderInfo) = when {
            activeVideoFormat and StarBridge.VIDEO_FORMAT_MASK_H264 != 0 -> {
                "video/avc" to avcDecoder.also {
                    if (it == null) {
                        severe("No available AVC decoder!")
                        return -1
                    }
                    if (initialWidth > 4096 || initialHeight > 4096) {
                        severe("> 4K streaming only supported on HEVC")
                        return -1
                    }
                }
            }

            activeVideoFormat and StarBridge.VIDEO_FORMAT_MASK_H265 != 0 -> "video/hevc" to hevcDecoder.also {
                if (it == null) {
                    severe("No available HEVC decoder!")
                    return -2
                }
            }

            activeVideoFormat and StarBridge.VIDEO_FORMAT_MASK_AV1 != 0 -> "video/av01" to av1Decoder.also {
                if (it == null) {
                    severe("No available AV1 decoder!")
                    return -2
                }
            }

            else -> {
                severe("Unknown format")
                return -3
            }
        }

        // These fix-ups only apply to H264 decoders
        when (selectedDecoderInfo) {
            avcDecoder -> {
                with(selectedDecoderInfo!!.name) {
                    needsSpsBitstreamFixup =
                        MediaCodecHelper.decoderNeedsSpsBitstreamRestrictions(this)
                    needsBaselineSpsHack = MediaCodecHelper.decoderNeedsBaselineSpsHack(this)
                    constrainedHighProfile =
                        MediaCodecHelper.decoderNeedsConstrainedHighProfile(this)
                    if (MediaCodecHelper.isExynos4Device()) {
                        info("Decoder $this is on Exynos 4")
                    }
                }
                refFrameInvalidationActive = refFrameInvalidationAvc
            }

            hevcDecoder -> {
                refFrameInvalidationActive = refFrameInvalidationHevc
            }

            av1Decoder -> {
                refFrameInvalidationActive = refFrameInvalidationAv1
            }
        }

        // Log decoder fix-up needs
        if (needsSpsBitstreamFixup) {
            info(
                "Decoder ${selectedDecoderInfo!!.name} needs SPS bitstream restrictions fixup"
            )
        }
        if (needsBaselineSpsHack) {
            info("Decoder ${selectedDecoderInfo!!.name} needs baseline SPS hack")
        }
        if (constrainedHighProfile) {
            info(
                "Decoder ${selectedDecoderInfo!!.name} needs constrained high profile"
            )
        }

        adaptivePlayback =
            MediaCodecHelper.decoderSupportsAdaptivePlayback(selectedDecoderInfo!!, mimeType)
        fusedIdrFrame = MediaCodecHelper.decoderSupportsFusedIdrFrame(selectedDecoderInfo, mimeType)

        var tryNumber = 0
        while (true) {
            info("Decoder configuration try: $tryNumber")
            val mediaFormat = createBaseMediaFormat(mimeType)

            val newFormat = MediaCodecHelper.setDecoderLowLatencyOptions(
                mediaFormat,
                selectedDecoderInfo,
                tryNumber
            )

            if (tryConfigureDecoder(
                    selectedDecoderInfo,
                    mediaFormat,
                    !newFormat && throwOnCodecError
                )
            ) {
                break // Success!
            }
            if (!newFormat) {
                return -5 // Couldn't configure without low latency options
            }
            tryNumber++
        }

        if (USE_FRAME_RENDER_TIME) {
            videoDecoder!!.setOnFrameRenderedListener({ _, presentationTimeUs, renderTimeNanos ->
                val delta = renderTimeNanos / 1000000L - presentationTimeUs / 1000
                if (delta in 0..999) {
                    activeWindowVideoStats.totalTimeMs += delta
                }
            }, null)
        }
        return 0
    }

    // Set up the parameters
    override fun setup(pixelFormat: Int, width: Int, height: Int, targetFrameRate: Int): Int {
        initialWidth = width
        initialHeight = height
        activeVideoFormat = pixelFormat
        refreshRate = targetFrameRate

        if (renderJob == null) {
            renderJob = applicationScope.launch {
                for (timestamp in renderChannel) {
                    val nextOutputBuffer = outputBufferQueue.poll()
                    if (nextOutputBuffer != null) {
                        renderOutputBuffer(nextOutputBuffer, timestamp)
                    }
                }
            }
        }

        return initializeDecoder(false)
    }

    // Check if the decoder needs to be recovered
    // All threads that interact with the MediaCodec instance must call this function regularly!
    private fun doCodecRecoveryIfRequired(quiescenceFlag: Int): Boolean {
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
            return false // No recovery needed
        }

        lock.withLock {
            if (choreographerJob == null) {
                codecRecoveryThreadQuiescedFlags =
                    codecRecoveryThreadQuiescedFlags or CR_FLAG_CHOREOGRAPHER
            }
            codecRecoveryThreadQuiescedFlags = codecRecoveryThreadQuiescedFlags or quiescenceFlag

            if (codecRecoveryThreadQuiescedFlags == CR_FLAG_ALL) {
                performCodecRecovery()
                codecRecoveryThreadQuiescedFlags = 0
                codecRecoveryMonitor.trySend(Unit)
            } else {
                waitForCodecRecovery()
            }
        }
        return true
    }

    // Perform the codec recovery
    private fun performCodecRecovery() {
        nextInputBuffer = null
        nextInputBufferIndex = -1
        outputBufferQueue.clear()

        try {
            when (codecRecoveryType.get()) {
                CR_RECOVERY_TYPE_FLUSH -> performCodecFlush()
                CR_RECOVERY_TYPE_RESTART -> performCodecRestart()
                CR_RECOVERY_TYPE_RESET -> performCodecReset()
            }

            if (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                codecRecoveryAttempts++
                info("Codec recovery attempt: $codecRecoveryAttempts")
            }
        } catch (e: Exception) {
            warning("Codec recovery failed: $e")
        }
    }

    // Perform the codec flush
    private fun performCodecFlush() {
        warning("Flushing decoder")
        try {
            videoDecoder!!.flush()
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
        } catch (e: IllegalStateException) {
            handleErrorAndUpgradeRecovery(e, CR_RECOVERY_TYPE_RESTART)
        }
    }

    // Perform the codec restart
    private fun performCodecRestart() {
        warning("Trying to restart decoder after CodecException")
        try {
            videoDecoder!!.stop()
            if (glesBridge != null) {
                glesBridge?.release()
                glesBridge = null
            }
            configureAndStartDecoder(configuredFormat)
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
        } catch (e: IllegalArgumentException) {
            handleSurfaceError(e)
        } catch (e: IllegalStateException) {
            handleErrorAndUpgradeRecovery(e, CR_RECOVERY_TYPE_RESET)
        }
    }

    // Perform the codec reset
    private fun performCodecReset() {
        warning("Trying to reset decoder after CodecException")
        try {
            videoDecoder!!.reset()
            if (glesBridge != null) {
                glesBridge?.release()
                glesBridge = null
            }
            configureAndStartDecoder(configuredFormat)
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
        } catch (e: IllegalArgumentException) {
            handleSurfaceError(e)
        } catch (_: IllegalStateException) {
            performCodecRecreation()
        }
    }

    // Perform the codec recreation
    private fun performCodecRecreation() {
        warning("Trying to recreate decoder after CodecException")
        videoDecoder!!.release()
        try {
            val err = initializeDecoder(true)
            check(err == 0) { "Decoder reset failed: $err" }
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
        } catch (e: IllegalArgumentException) {
            handleSurfaceError(e)
        } catch (e: IllegalStateException) {
            handleUnrecoverableError(e)
        }
    }

    // Handle an error and upgrade the recovery type
    private fun handleErrorAndUpgradeRecovery(e: Exception, upgradeType: Int) {
        e.printStackTrace()
        codecRecoveryType.set(upgradeType)
    }

    // Handle a surface error
    private fun handleSurfaceError(e: Exception) {
        e.printStackTrace()
        stopping = true
        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
    }

    // Handle an unrecoverable error
    private fun handleUnrecoverableError(e: IllegalStateException) {
        if (!reportedCrash) {
            reportedCrash = true
            crashListener.notifyCrash(e)
        }
        throw RendererException(this, e)
    }

    // Wait for the decoder to quiesce
    private fun waitForCodecRecovery() {
        info("Waiting to quiesce decoder threads: $codecRecoveryThreadQuiescedFlags")
        while (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
            try {
                codecRecoveryMonitor.tryReceive()
            } catch (e: InterruptedException) {
                e.printStackTrace()
                break
            }
        }
    }

    // Handle a decoder exception
    private fun handleDecoderException(e: IllegalStateException): Boolean {
        // Eat decoder exceptions if we're in the process of stopping
        if (stopping) {
            return false
        }

        // Handle CodecException (if applicable)
        if (e is MediaCodec.CodecException) {
            if (e.isTransient) {
                warning(e.diagnosticInfo) // Log transient exceptions and continue
                return true
            }
            severe(e.diagnosticInfo) // Log severe exceptions

            if (codecRecoveryAttempts < CR_MAX_TRIES) {
                handleCodecExceptionRecovery(e)
                return false // Recovery will be handled later
            }
        } else {
            // Handle IllegalStateException (legacy, requires to be reset)
            if (codecRecoveryAttempts < CR_MAX_TRIES) {
                handleLegacyExceptionRecovery(e)
                return false // Recovery will be handled later
            }
        }

        // Handle exceptions outside of recovery attempts
        return handleExceptionOutsideRecovery(e)
    }

    // Handle a CodecException
    private fun handleCodecExceptionRecovery(e: MediaCodec.CodecException) {
        val recoveryType = if (e.isRecoverable) {
            CR_RECOVERY_TYPE_RESTART // Attempt restart for recoverable exceptions
        } else {
            CR_RECOVERY_TYPE_RESET // Full reset for non-recoverable exceptions
        }

        updateCodecRecoveryType(recoveryType, e)
    }

    // Handle legacy IllegalStateException
    private fun handleLegacyExceptionRecovery(e: IllegalStateException) {
        updateCodecRecoveryType(CR_RECOVERY_TYPE_RESET, e) // Legacy exceptions always require reset
    }

    // Update the codec recovery type
    private fun updateCodecRecoveryType(@CodecRecoveryType newRecoveryType: Int, e: Exception) {
        when (codecRecoveryType.get()) {
            CR_RECOVERY_TYPE_NONE -> {
                info(
                    "Decoder requires ${recoveryTypeName(newRecoveryType)} for ${e.javaClass.simpleName}"
                )
                codecRecoveryType.set(newRecoveryType)
            }

            CR_RECOVERY_TYPE_FLUSH -> {
                info(
                    "Decoder flush promoted to ${recoveryTypeName(newRecoveryType)} for ${e.javaClass.simpleName}"
                )
                codecRecoveryType.set(newRecoveryType)
            }

            CR_RECOVERY_TYPE_RESTART -> {
                if (newRecoveryType == CR_RECOVERY_TYPE_RESET) {
                    info(
                        "Decoder restart promoted to reset for ${e.javaClass.simpleName}"
                    )
                    codecRecoveryType.set(newRecoveryType)
                } else {
                    check(codecRecoveryType.get() == newRecoveryType) { "Unexpected codec recovery type: ${codecRecoveryType.get()}" }
                }
            }

            CR_RECOVERY_TYPE_RESET -> {
                check(codecRecoveryType.get() == newRecoveryType) { "Unexpected codec recovery type: ${codecRecoveryType.get()}" }
            }
        }
        e.printStackTrace()
    }

    // Handle an illegal state exception
    private fun handleExceptionOutsideRecovery(e: IllegalStateException): Boolean {
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
            // Handle potential race condition with decoder/surface teardown
            if (initialException != null) {
                if (SystemClock.uptimeMillis() - initialExceptionTimestamp >= EXCEPTION_REPORT_DELAY_MS) {
                    if (!reportedCrash) {
                        reportedCrash = true
                        crashListener.notifyCrash(initialException)
                    }
                    throw initialException as RendererException
                }
            } else {
                initialException = RendererException(this, e)
                initialExceptionTimestamp = SystemClock.uptimeMillis()
            }
        }
        return false // Not transient
    }

    // Get the name of the recovery type
    private fun recoveryTypeName(@CodecRecoveryType recoveryType: Int): String {
        return when (recoveryType) {
            CR_RECOVERY_TYPE_FLUSH -> "flush"
            CR_RECOVERY_TYPE_RESTART -> "restart"
            CR_RECOVERY_TYPE_RESET -> "reset"
            else -> "none"
        }
    }

    // Handle the frame creation
    override fun doFrame(frameTimeNanos: Long) {

        // Request next frame callback if using balanced frame pacing
        if (refreshRate <= 0) {
            // Request the next frame callback to try again on the next vsync
            if (prefs.framePacing == FRAME_PACING_BALANCED) {
                choreographer?.postFrameCallback(this@MediaCodecDecoderRenderer)
            }
            return // Exit early
        }

        // Early exit if stopping
        if (stopping) return

        val frameTimeNanosAdjusted = (frameTimeNanos - DisplayManagerCompat.getInstance(activity)
            .getDisplay(Display.DEFAULT_DISPLAY)?.appVsyncOffsetNanos!!)

        // Calculate time deltas with descriptive names
        val timeSinceLastFrameNs = frameTimeNanosAdjusted - lastRenderedFrameTimeNanos
        val targetFrameIntervalNs = (1_000_000_000 / refreshRate).toLong()
        val thresholdForRenderingNs = targetFrameIntervalNs * 8 / 10 // 80% of expected interval

        // Render if enough time has passed
        if (timeSinceLastFrameNs >= thresholdForRenderingNs) {
            renderChannel.trySend(frameTimeNanosAdjusted)
        }

        // Attempt codec recovery (regardless of rendering)
        if (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
            doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER)
        }

        if (prefs.framePacing == FRAME_PACING_BALANCED) {
            if (choreographer != null) {
                choreographer!!.postFrameCallback(this@MediaCodecDecoderRenderer)
            }
        }
    }

    // Render the output buffer
    private fun renderOutputBuffer(buffer: Int?, presentationTimeNs: Long) {
        try {
            videoDecoder!!.releaseOutputBuffer(buffer!!, presentationTimeNs)
            if (prefs.videoSr && glesBridge != null) {
                glesBridge!!.renderFrame(presentationTimeNs)
            }
            lastRenderedFrameTimeNanos = presentationTimeNs
            activeWindowVideoStats.totalFramesRendered++
        } catch (ignored: IllegalStateException) {
            // Handle rendering failure gracefully
            try {
                videoDecoder!!.releaseOutputBuffer(buffer!!, false)
            } catch (e: IllegalStateException) {
                e.printStackTrace()
                handleDecoderException(e) // Centralized exception handling
            }
        }
    }

    // Start the Choreographer
    private fun launchChoreographer() {
        if (prefs.framePacing != FRAME_PACING_BALANCED) return
        if (choreographerJob?.isActive == true) return

        choreographerJob = applicationScope.launch(choreographerDispatcher) {
            // This block runs on our dedicated "Choreographer-Thread"
            try {
                // Manually prepare a Looper for this thread.
                Looper.prepare()

                // Now that a Looper exists, we can safely get a Choreographer instance.
                createChoreographerInstanceInternal()
                info(
                    "Choreographer instance created on dedicated thread with a new Looper."
                )

                // Register the frame callback.
                choreographer!!.postFrameCallback(this@MediaCodecDecoderRenderer)
                info("Choreographer frame callback registered.")

                // Start the message loop. This will block the thread until the
                // Looper is quit. It's the coroutine equivalent of the thread
                // just sitting and waiting.
                Looper.loop()

            } catch (e: CancellationException) {
                // This is the normal, expected way to exit when the job is canceled.
                info("Choreographer job is cancelling.")
            }
        }
    }

    // Start the renderer
    override fun launchRenderer() {
        startRendering()
    }

    fun startRendering() {
        startRenderingJob = applicationScope.launch {
            val info = MediaCodec.BufferInfo()
            while (isActive && !stopping) {
                try {
                    val outIndex = videoDecoder!!.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                    if (outIndex >= 0) {
                        processOutputBuffer(outIndex, info)
                    } else {
                        handleDecoderInfo(outIndex)
                    }
                } catch (e: IllegalStateException) {
                    handleDecoderException(e)
                } finally {
                    if (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                        doCodecRecoveryIfRequired(CR_FLAG_RENDER_JOB)
                    }
                }
            }
        }.apply { info("Renderer job running $RENDER_JOB_NAME") }
    }

    // Process the output buffer
    private fun processOutputBuffer(outIndex: Int, info: MediaCodec.BufferInfo) {
        val presentationTimeUs = info.presentationTimeUs
        numFramesOut++

        if (prefs.framePacing != FRAME_PACING_BALANCED) {
            handleNonBalancedFramePacing(outIndex, info)
        } else {
            handleBalancedFramePacing(outIndex)
        }

        // Update decode latency stats using real enqueue->dequeue time
        updateDecodeLatencyStats(presentationTimeUs)
    }

    // Update stats using real decode time: enqueue->dequeue, instead of uptime - PTS
    private fun updateDecodeLatencyStats(presentationTimeUs: Long) {
        val enqNs = enqueueNsByPtsUs[presentationTimeUs]
        if (enqNs != null) {
            enqueueNsByPtsUs.remove(presentationTimeUs)
            val decMs = (System.nanoTime() - enqNs) / 1000000L
            if (decMs in 0..1000) {
                if (prefs.videoSr && glesBridge != null && prefs.framePacing != FRAME_PACING_BALANCED) {
                    glesBridge!!.renderFrame(0)
                }
                activeWindowVideoStats.decoderTimeMs += decMs
                if (!USE_FRAME_RENDER_TIME) {
                    activeWindowVideoStats.totalTimeMs += decMs
                }
            }
        }
    }

    // Handle frame pacing when not in balanced mode
    private fun handleNonBalancedFramePacing(lastIndex: Int, info: MediaCodec.BufferInfo) {
        var finalIndex = lastIndex
        var outIndex: Int
        while (videoDecoder!!.dequeueOutputBuffer(info, 0).also { outIndex = it } >= 0) {
            videoDecoder!!.releaseOutputBuffer(finalIndex, false)
            numFramesOut++
            finalIndex = outIndex
        }

        when (prefs.framePacing) {
            FRAME_PACING_MAX_SMOOTHNESS,
            FRAME_PACING_CAP_FPS -> {
                videoDecoder!!.releaseOutputBuffer(finalIndex, true)
            }

            else -> {
                videoDecoder!!.releaseOutputBuffer(finalIndex, System.nanoTime())
            }
        }
        activeWindowVideoStats.totalFramesRendered++
    }

    // Handle frame pacing in balanced mode
    private fun handleBalancedFramePacing(lastIndex: Int) {
        if (outputBufferQueue.size == OUTPUT_BUFFER_QUEUE_LIMIT) {
            try {
                videoDecoder!!.releaseOutputBuffer(outputBufferQueue.take(), false)
            } catch (_: InterruptedException) {
                severe("Output buffer queue interrupted")
                return
            }
        }
        outputBufferQueue.add(lastIndex)
    }

    // Handle decoder info messages
    private fun handleDecoderInfo(outIndex: Int) {
        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            info("Output format changed")
            outputFormat = videoDecoder!!.outputFormat
            info("New output format: $outputFormat")
        }
    }

    // Fetch the next input buffer
    private fun fetchNextInputBuffer(): Boolean {
        if (nextInputBuffer != null) {
            // Buffer already obtained
            return true
        }

        var codecRecovered = false
        val startTime = SystemClock.uptimeMillis()

        try {
            // Try to dequeue a buffer, with a timeout to avoid infinite blocking
            while (nextInputBufferIndex < 0 && !stopping) {
                nextInputBufferIndex = videoDecoder!!.dequeueInputBuffer(10000)
            }

            // If we got a buffer index, retrieve the actual buffer
            if (nextInputBufferIndex >= 0) {
                nextInputBuffer = videoDecoder!!.getInputBuffer(nextInputBufferIndex)
                if (nextInputBuffer == null) {
                    // Buffer retrieval failed, reset index
                    nextInputBufferIndex = -1
                }
            }
        } catch (e: IllegalStateException) {
            handleDecoderException(e)
            // Could not fetch due to decoder error
            return false
        } finally {
            // Check if codec recovery was needed (assuming doCodecRecoveryIfRequired is defined elsewhere)
            if (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD)
            }
        }

        if (codecRecovered) {
            // Codec recovery was needed, return false
            return false
        }

        // Log a warning if dequeue took too long
        val dequeueTimeMs = (SystemClock.uptimeMillis() - startTime).toInt()
        if (dequeueTimeMs >= 20) {
            warning("Dequeue input buffer took long: $dequeueTimeMs ms")
        }

        // Handle cases where no buffer was obtained
        if (nextInputBuffer == null) {
            if (dequeueTimeMs >= 5000 && initialException == null) {
                // If dequeue took very long, consider it a decoder hang
                val decoderHungException = DecoderHungException(dequeueTimeMs)
                if (!reportedCrash) {
                    reportedCrash = true
                    // Report the crash
                    crashListener.notifyCrash(decoderHungException)
                }
                // Stop rendering due to hang
                throw RendererException(this, decoderHungException)
            }
            // No buffer obtained, return false
            return false
        }
        // Buffer obtained, return true
        return true
    }

    // Create the Choreographer instance
    private fun createChoreographerInstanceInternal() {
        choreographer = Choreographer.getInstance()
    }

    // Prepare for stop
    fun prepareForStop() {
        // Let the decoding code know to ignore codec exceptions now
        stopping = true

        // Stop any active codec recovery operations
        lock.withLock {
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
            (codecRecoveryMonitor).trySend(Unit)
        }

        // Post a quit message to the Choreographer looper and remove the callback
        if (choreographer != null) {
            terminateChoreographer()
        }
    }

    // Stop the Choreographer
    private fun terminateChoreographer() {

        // Cancel the Choreographer job
        choreographerJob?.cancel()
        info("Choreographer job stopped")

        // Safely unregister the callback
        choreographer?.removeFrameCallback(this@MediaCodecDecoderRenderer)
        info("Unregistered choreographer frame callback.")

        if (::choreographerDispatcher.isInitialized) {
            choreographerDispatcher.close()
        }

        // Quit the Choreographer looper
        Looper.myLooper()?.quit()
        info("Choreographer Looper has been quit.")

        // Immediately release references to avoid potential issues
        choreographerJob = null
        choreographer = null
    }

    // Stop the renderer
    override fun stop() {
        stopRendering()
    }

    fun stopRendering() {
        // May be called already, we can call prepareForStop() even if we're already stopping
        prepareForStop()

        // Cancel the rendering job
        startRenderingJob?.cancel()
        renderJob?.cancel()
        info("Renderer jobs stopped")

        startRenderingJob = null
        renderJob = null
    }

    // Release the codec
    override fun release() {
        videoDecoder?.let { decoder ->

            // Reset state variables
            initialExceptionTimestamp = 0L
            codecRecoveryThreadQuiescedFlags = 0
            codecRecoveryAttempts = 0
            refreshRate = 0
            lastRenderedFrameTimeNanos = 0L
            numSpsIn = 0
            numPpsIn = 0
            numVpsIn = 0
            numFramesIn = 0
            numFramesOut = 0
            nextInputBufferIndex = -1
            lastFrameNumber = 0
            lastTimestampUs = 0L

            // Clear collections
            vpsBuffers.clear()
            spsBuffers.clear()
            ppsBuffers.clear()
            outputBufferQueue.clear()

            // Reset other variables
            nextInputBuffer = null
            reportedCrash = false
            needsBaselineSpsHack = false
            foreground = false
            USE_FRAME_RENDER_TIME = false
            FRAME_RENDER_TIME_ONLY = false

            // Release the decoder
            decoder.release()
            videoDecoder = null

            if (glesBridge != null) {
                glesBridge!!.release()
                glesBridge = null
            }
        }
    }

    // Set the HDR mode
    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {

        val newHdrMetadata = if (enabled && hdrMetadata != null) hdrMetadata else null

        // Check if HDR metadata has actually changed
        if (currentHdrMetadata.contentEquals(newHdrMetadata)) {
            return
        }

        currentHdrMetadata = newHdrMetadata

        // Trigger codec restart for HDR metadata change. This will happen on the next input or output buffer.
        codecRecoveryAttempts = 0 // Reset recovery attempts for expected "recovery"

        // Prioritize Restart over Flush if a recovery is already pending
        if (!codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
            codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART)
        }
    }

    // Queue the input buffer
    private fun queueNextInputBuffer(timestampUs: Long, codecFlags: Int): Boolean {

        var codecRecovered = false

        try {
            nextInputBuffer?.let {
                videoDecoder!!.queueInputBuffer(
                    nextInputBufferIndex,
                    0, it.position(),
                    timestampUs, codecFlags
                )

                // Track enqueue time for this PTS
                try {
                    enqueueNsByPtsUs.put(timestampUs, System.nanoTime())
                } catch (ignored: Throwable) { }

                // Reset buffer tracking
                nextInputBufferIndex = -1
                nextInputBuffer = null
            } ?: return false
        } catch (e: IllegalStateException) {
            if (handleDecoderException(e)) {
                // Transient error: Clear and retain the buffer
                nextInputBuffer?.clear()
            } else {
                // Non-transient error: Release the buffer
                nextInputBufferIndex = -1
                nextInputBuffer = null
            }
            // Signal the need for recovery
            return false
        } finally {
            if (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD)
            }
        }

        // Prioritize codec recovery over fetching a new buffer
        return !codecRecovered && fetchNextInputBuffer()
        // Signal the need for an IDR frame
    }

    // Patch the SPS
    private fun doProfileSpecificSpsPatching(sps: SeqParameterSet?) {
        sps ?: return

        val isConstrainedHighProfile = sps.profileIdc == 100 && constrainedHighProfile

        if (isConstrainedHighProfile) {
            info("Setting constraint set flags for constrained high profile")
            sps.constraintSet4Flag = true
            sps.constraintSet5Flag = true
        } else {
            // Force the constraints unset otherwise (some may be set by default)
            sps.constraintSet4Flag = false
            sps.constraintSet5Flag = false
        }
    }

    // Helper function to reset CSD data for each IDR frame
    private fun resetCsdData(frameNumber: Int, frameType: Int) {
        if (frameType == StarBridge.FRAME_TYPE_IDR && lastFrameNumber != frameNumber) {
            vpsBuffers.clear()
            spsBuffers.clear()
            ppsBuffers.clear()
        }
        lastFrameNumber = frameNumber
    }

    // Helper function to flip the stats
    @SuppressLint("StringFormatInvalid")
    private fun flipStats() {

        val currentTime = SystemClock.uptimeMillis()
        if (currentTime >= activeWindowVideoStats.measurementStartTimestamp + 1000) {
            if (prefs.enablePerfOverlay or viewModel.getStatsRequired()) {

                // Calculate aggregated stats
                val lastTwo = VideoStats()
                lastTwo.add(lastWindowVideoStats)
                lastTwo.add(activeWindowVideoStats)
                val fps = lastTwo.getFps()
                val decodeTimeMs = lastTwo.decoderTimeMs.toFloat() / lastTwo.totalFramesReceived
                val rttInfo = getEstimatedRttInfo()

                val perfString = """
                ${
                    context.getString(
                        R.string.perf_overlay_streamdetails,
                        "$initialWidth x $initialHeight",
                        fps.totalFps
                    )
                }
                ${context.getString(R.string.perf_overlay_decoder, getDecoderName())}
                ${context.getString(R.string.perf_overlay_incomingfps, fps.receivedFps)}
                ${context.getString(R.string.perf_overlay_renderingfps, fps.renderedFps)}
                ${
                    context.getString(
                        R.string.perf_overlay_netdrops,
                        lastTwo.framesLost.toFloat() / lastTwo.totalFrames * 100
                    )
                }
                ${
                    context.getString(
                        R.string.perf_overlay_netlatency,
                        (rttInfo shr 32).toInt(),
                        rttInfo.toInt()
                    )
                }
                ${
                    if (lastTwo.framesWithHostProcessingLatency > 0) context.getString(
                        R.string.perf_overlay_hostprocessinglatency,
                        lastTwo.minHostProcessingLatency.code.toFloat() / 10,
                        lastTwo.maxHostProcessingLatency.code.toFloat() / 10,
                        lastTwo.totalHostProcessingLatency.toFloat() / 10 / lastTwo.framesWithHostProcessingLatency
                    ) else ""
                }
                ${context.getString(R.string.perf_overlay_dectime, decodeTimeMs)}
                ${
                    if (prefs.videoSr) {
                        context.getString(
                            R.string.perf_overlay_video_enhancement,
                            VIDEO_SR_UPSCALE_RATIO
                        )
                    } else ""
                }
            """.trimIndent()

                perfListener.onPerfUpdate(perfString)
            }
            // Update global and window stats
            globalVideoStats.add(activeWindowVideoStats)
            lastWindowVideoStats.copy(activeWindowVideoStats)
            activeWindowVideoStats.clear()
            activeWindowVideoStats.measurementStartTimestamp = currentTime
        }
    }

    // Helper function to submit the NLU frame
    private fun submitNluFrame(frameNumber: Int) {
        if (lastFrameNumber == 0) {
            activeWindowVideoStats.measurementStartTimestamp = SystemClock.uptimeMillis()
        } else if (frameNumber > lastFrameNumber + 1) {

            // We only care if the frame number is greater than expected.
            val lostFrames = frameNumber - lastFrameNumber - 1
            activeWindowVideoStats.framesLost += lostFrames
            activeWindowVideoStats.totalFrames += lostFrames
            activeWindowVideoStats.frameLossEvents++
        }
        // Update lastFrameNumber to track the latest frame
        lastFrameNumber = frameNumber
    }

    // Helper function to handle SPS
    private fun handleH264Sps(decodeUnitData: ByteArray, decodeUnitLength: Int): Int {

        val spsBuf = ByteBuffer.wrap(decodeUnitData)
        val startSeqLen = if (decodeUnitData[2].toInt() == 0x01) 3 else 4

        // Skip to the start of the NALU data
        spsBuf.position(startSeqLen + 1)

        // Parse the SPS
        val sps = H264Utils.readSPS(spsBuf)

        // Adjust Level IDC for Reduced Buffering (if applicable)
        if (!refFrameInvalidationActive) {
            sps.levelIdc = when {
                initialWidth <= 720 && initialHeight <= 480 && refreshRate <= 60 -> 31 // Max 5 buffered frames at 720x480x60
                initialWidth <= 1280 && initialHeight <= 720 && refreshRate <= 60 -> 32 // Max 5 buffered frames at 1280x720x60
                initialWidth <= 1920 && initialHeight <= 1080 && refreshRate <= 60 -> 42 // Max 4 buffered frames at 1920x1080x64
                else -> sps.levelIdc // No change needed
            }.also {
                if (it != sps.levelIdc) info("Patching level_idc to $it")
            }
        }

        // Force Reference Frame Count to 1 (if applicable)
        if (!refFrameInvalidationActive) {
            info("Patching num_ref_frames in SPS to 1")
            sps.numRefFrames = 1
        }

        // Add or Patch VUI Parameters
        if (sps.vuiParams == null) {
            info("Adding VUI parameters")
            sps.vuiParams = VUIParameters()
        }

        // Add or Patch Bitstream Restrictions
        if (sps.vuiParams.bitstreamRestriction == null) {
            info("Adding bitstream restrictions")
            sps.vuiParams.bitstreamRestriction = BitstreamRestriction().apply {
                motionVectorsOverPicBoundariesFlag = true
                maxBytesPerPicDenom = 2
                maxBitsPerMbDenom = 1
                log2MaxMvLengthHorizontal = 16
                log2MaxMvLengthVertical = 16
                numReorderFrames = 0
            }
        } else {
            info("Patching bitstream restrictions")
        }

        // Ensure maxDecFrameBuffering is not less than numRefFrames
        sps.vuiParams.bitstreamRestriction.maxDecFrameBuffering = maxOf(
            sps.vuiParams.bitstreamRestriction.maxDecFrameBuffering,
            sps.numRefFrames
        )

        // Apply Baseline SPS Hack (if needed)
        if (needsBaselineSpsHack) {
            info("Hacking SPS to baseline")
            sps.profileIdc = 66
            savedSps = sps
        }

        // Apply Profile-Specific SPS Patching
        doProfileSpecificSpsPatching(sps)

        // Write the modified SPS
        val escapedNalu = H264Utils.writeSPS(sps, decodeUnitLength)

        // Construct the patched SPS NALU
        val naluBuffer = ByteArray(startSeqLen + 1 + escapedNalu.limit()).apply {
            System.arraycopy(decodeUnitData, 0, this, 0, startSeqLen + 1)
            escapedNalu[this, startSeqLen + 1, escapedNalu.limit()]
        }

        // Batch the SPS for later submission
        spsBuffers.add(naluBuffer)

        return StarBridge.DR_OK
    }

    // Helper function to handle VPS buffer
    private fun handleVpsBuffer(decodeUnitData: ByteArray, decodeUnitLength: Int): Int {
        // Batch this to submit together with other CSD per AOSP docs
        val naluBuffer = decodeUnitData.copyOfRange(0, decodeUnitLength)
        vpsBuffers.add(naluBuffer)
        return StarBridge.DR_OK
    }

    // Helper function to handle SPS buffer
    private fun handleSpsBuffer(decodeUnitData: ByteArray, decodeUnitLength: Int): Int {
        // Batch this to submit together with other CSD per AOSP docs
        val naluBuffer = decodeUnitData.copyOfRange(0, decodeUnitLength)
        spsBuffers.add(naluBuffer)
        return StarBridge.DR_OK
    }

    // Helper function to handle PPS buffer
    private fun handlePpsBuffer(decodeUnitData: ByteArray, decodeUnitLength: Int): Int {
        // Batch this to submit together with other CSD per AOSP docs
        val naluBuffer = decodeUnitData.copyOfRange(0, decodeUnitLength)
        ppsBuffers.add(naluBuffer)
        return StarBridge.DR_OK
    }

    // Helper function to handle non-IDR frames
    private fun handleNonIdrFrame(frameType: Int): Int {

        var starBridgeFrameType = 0

        if (!submittedCsd && !fusedIdrFrame) {

            if (!fetchNextInputBuffer()) {
                starBridgeFrameType = StarBridge.DR_NEED_IDR
            }

            // Submit all CSD when we receive the first non-CSD blob in an IDR frame
            submitCsdBlobs()

            if (!queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) {
                starBridgeFrameType = StarBridge.DR_NEED_IDR
            }

            // Remember that we already submitted CSD for this frame, so we don't do it again in the fused IDR case below.
            csdSubmittedForThisFrame = true

            // Remember that we submitted CSD globally for this MediaCodec instance
            submittedCsd = true
            if (needsBaselineSpsHack) {
                needsBaselineSpsHack = false
                if (!replaySps()) {
                    starBridgeFrameType = StarBridge.DR_NEED_IDR
                }
                info("SPS replay complete")
            }
        }
        return starBridgeFrameType
    }

    // Helper function to submit all CSD blobs
    private fun submitCsdBlobs() {
        vpsBuffers.forEach { nextInputBuffer!!.put(it) }
        spsBuffers.forEach { nextInputBuffer!!.put(it) }
        ppsBuffers.forEach { nextInputBuffer!!.put(it) }
    }

    // Helper function to handle IDR frame logic
    private fun handleStarBridgeFrameType(
        frameType: Int,
        decodeUnitData: ByteArray,
        decodeUnitLength: Int,
        decodeUnitType: Int
    ): Int {
        var starBridgeFrameType = 0

        if (frameType == StarBridge.FRAME_TYPE_IDR) {
            starBridgeFrameType = when (decodeUnitType) {
                StarBridge.BUFFER_TYPE_SPS -> {
                    if (activeVideoFormat and StarBridge.VIDEO_FORMAT_MASK_H264 != 0) {
                        numSpsIn++
                        handleH264Sps(decodeUnitData, decodeUnitLength)
                    } else {
                        numSpsIn++
                        handleSpsBuffer(decodeUnitData, decodeUnitLength)
                    }
                }

                StarBridge.BUFFER_TYPE_VPS -> {
                    numVpsIn++
                    handleVpsBuffer(decodeUnitData, decodeUnitLength)
                }

                StarBridge.BUFFER_TYPE_PPS -> {
                    numPpsIn++
                    handlePpsBuffer(decodeUnitData, decodeUnitLength)
                }

                else -> {
                    if (activeVideoFormat and (StarBridge.VIDEO_FORMAT_MASK_H264 or StarBridge.VIDEO_FORMAT_MASK_H265) != 0) {
                        handleNonIdrFrame(frameType)
                    } else {
                        StarBridge.DR_OK
                    }
                }
            }
        }
        return starBridgeFrameType
    }

    // Helper function to update video statistics
    private fun updateVideoStats(
        frameHostProcessingLatency: Char,
        receiveTimeMs: Long,
        enqueueTimeMs: Long
    ) {
        if (frameHostProcessingLatency.code != 0) {
            if (activeWindowVideoStats.minHostProcessingLatency.code != 0) {
                activeWindowVideoStats.minHostProcessingLatency = min(
                    activeWindowVideoStats.minHostProcessingLatency.code.toDouble(),
                    frameHostProcessingLatency.code.toDouble()
                ).toInt().toChar()
            } else {
                activeWindowVideoStats.minHostProcessingLatency = frameHostProcessingLatency
            }
            activeWindowVideoStats.framesWithHostProcessingLatency += 1
        }
        activeWindowVideoStats.maxHostProcessingLatency = max(
            activeWindowVideoStats.maxHostProcessingLatency.code.toDouble(),
            frameHostProcessingLatency.code.toDouble()
        ).toInt().toChar()
        activeWindowVideoStats.totalHostProcessingLatency += frameHostProcessingLatency.code
        activeWindowVideoStats.totalFramesReceived++
        activeWindowVideoStats.totalFrames++
        if (!FRAME_RENDER_TIME_ONLY) {
            // Count time from first packet received to enqueue time as receive time
            // We will count DU queue time as part of decoding, because it is directly
            // caused by a slow decoder.
            activeWindowVideoStats.totalTimeMs += enqueueTimeMs - receiveTimeMs
        }
    }

    // Helper function to submit the decode unit
    override fun submitDecodeUnit(
        decodeUnitData: ByteArray, decodeUnitLength: Int,
        decodeUnitType: Int, frameNumber: Int, frameType: Int, frameHostProcessingLatency: Char,
        receiveTimeUs: Long, enqueueTimeUs: Long
    ): Int {

        if (stopping) {
            // Don't bother if we're stopping
            return StarBridge.DR_OK
        }

        // Submit the NLU frame
        submitNluFrame(frameNumber)

        // Reset CSD data for each IDR frame
        resetCsdData(frameNumber, frameType)

        // Flip stats windows roughly every second
        flipStats()

        // Handle IDR frames
        if (frameType == StarBridge.FRAME_TYPE_IDR) {
            handleStarBridgeFrameType(frameType, decodeUnitData, decodeUnitLength, decodeUnitType)
        }

        // Update video stats
        updateVideoStats(frameHostProcessingLatency, receiveTimeUs, enqueueTimeUs)

        if (!fetchNextInputBuffer()) {
            return StarBridge.DR_NEED_IDR
        }

        // Get the codec flags
        val (codecFlags, timestampUs) = pair(frameType, enqueueTimeUs, decodeUnitLength)

        // Copy data from our buffer list into the input buffer
        nextInputBuffer!!.put(decodeUnitData, 0, decodeUnitLength)
        return if (!queueNextInputBuffer(timestampUs, codecFlags)) {
            StarBridge.DR_NEED_IDR
        } else StarBridge.DR_OK
    }

    // Helper function to pair the frame type, enqueue time, and decode unit length
    private fun pair(frameType: Int, enqueueTimeUs: Long, decodeUnitLength: Int): Pair<Int, Long> {

        val codecFlags = if (frameType == StarBridge.FRAME_TYPE_IDR) {
            MediaCodec.BUFFER_FLAG_KEY_FRAME.also {
                // If we are using fused IDR frames, submit the CSD with each IDR frame
                if (fusedIdrFrame && !csdSubmittedForThisFrame) {
                    submitCsdBlobs()
                }
            }
        } else {
            StarBridge.DR_OK
        }


        // The frame timestamps use an undefined epoch, so normalize them to uptime microseconds.
        if (baseTimestampUs == 0L) {
            baseTimestampUs = (SystemClock.uptimeMillis() * 1000) - enqueueTimeUs
        }

        var timestampUs = baseTimestampUs + enqueueTimeUs

        // Calculate timestamp, ensuring monotonicity
        if (timestampUs <= lastTimestampUs) {
            timestampUs = lastTimestampUs + 1
        }
        lastTimestampUs = timestampUs

        // Validate decode unit length
        numFramesIn++

        val remainingCapacity = nextInputBuffer!!.limit() - nextInputBuffer!!.position()
        if (decodeUnitLength > remainingCapacity) {
            val exception = IllegalArgumentException(
                "Decode unit length $decodeUnitLength too large for input buffer ${nextInputBuffer!!.limit()}"
            )
            if (!reportedCrash) {
                reportedCrash = true
                crashListener.notifyCrash(exception)
            }
            throw RendererException(this, exception)
        }

        return codecFlags to timestampUs
    }

    // Helper function to get the decoder string
    private fun getDecoderName(): String = when {
        activeVideoFormat and StarBridge.VIDEO_FORMAT_MASK_H264 != 0 -> (avcDecoder?.name + " (H264)")
        activeVideoFormat and StarBridge.VIDEO_FORMAT_MASK_H265 != 0 -> (hevcDecoder?.name + " (HEVC)")
        activeVideoFormat and StarBridge.VIDEO_FORMAT_MASK_AV1 != 0 -> (av1Decoder?.name + " (AV1)")
        else -> "(unknown)"
    }

    // Helper function to replay the SPS
    private fun replaySps(): Boolean {
        // Guard clause for early exit
        val sps = savedSps ?: return false

        if (!fetchNextInputBuffer()) {
            return false
        }

        nextInputBuffer!!.apply {
            // Write Annex B header// Configure SPS for replay
            put(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67))
            // Switch H264 profile back to high
            sps.profileIdc = 100
            // Patch constraint flags
            doProfileSpecificSpsPatching(sps)

            put(H264Utils.writeSPS(sps, 128)) // Write escaped SPS NALU
        }

        // Clear saved SPS
        savedSps = null

        return queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) // Queue the new SPS
    }

    // Get the capabilities
    override fun getCapabilities(): Int {

        // Use bitwise OR assignments for conciseness
        var capabilities = CAPABILITY_SLICES_PER_FRAME(optimalSlicesPerFrame)
        if (refFrameInvalidationAvc) capabilities =
            capabilities or StarBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC
        if (refFrameInvalidationHevc) capabilities =
            capabilities or StarBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC
        if (refFrameInvalidationAv1) capabilities =
            capabilities or StarBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1

        if (directSubmit) capabilities = capabilities or StarBridge.CAPABILITY_DIRECT_SUBMIT

        return capabilities
    }

    // Get the average end-to-end latency
    fun averageEndToEndLatency(): Int {
        return if (globalVideoStats.totalFramesReceived == 0) {
            0
        } else {
            (globalVideoStats.totalTimeMs / globalVideoStats.totalFramesReceived).toInt()
        }
    }

    // Get the average hardware decoder latency
    fun averageDecoderLatency(): Int {
        return if (globalVideoStats.totalFramesReceived == 0) {
            0
        } else {
            (globalVideoStats.decoderTimeMs / globalVideoStats.totalFramesReceived).toInt()
        }
    }

    // Decoder exception class
    internal class DecoderHungException(private val hangTimeMs: Int) : RuntimeException() {
        override val message: String
            get() = buildString {
                append("Hang time: ")
                append(hangTimeMs)
                append(" ms")
                append(RendererException.DELIMITER)
                append(super.message)
            }
    }

    // Render exception class
    internal class RendererException(renderer: MediaCodecDecoderRenderer, e: Exception) :
        RuntimeException() {

        private val errorMessage: String = generateErrorMessage(renderer, e)

        override fun toString(): String {
            return errorMessage
        }

        // Helper function to generate the error message
        private fun generateErrorMessage(
            renderer: MediaCodecDecoderRenderer,
            originalException: Exception
        ): String {

            val stringBuilder = StringBuilder()

            val errorCategory = when {
                renderer.numVpsIn == 0 && renderer.numSpsIn == 0 && renderer.numPpsIn == 0 -> PRE_SPS_ERROR
                renderer.numSpsIn > 0 && renderer.numPpsIn == 0 -> PRE_PPS_ERROR
                renderer.numPpsIn > 0 && renderer.numFramesIn == 0 -> PRE_IFRAME_ERROR
                renderer.numFramesIn > 0 && renderer.outputFormat == null -> PRE_OUTPUT_CONFIG_ERROR
                renderer.outputFormat != null && renderer.numFramesOut == 0 -> PRE_OUTPUT_ERROR
                renderer.numFramesOut <= renderer.refreshRate * 30 -> EARLY_OUTPUT_ERROR
                else -> ERROR_WHILE_STREAMING
            }
            stringBuilder.appendLine("Error Category: $errorCategory")

            stringBuilder.appendLine(
                "Format: ${
                    String.format(
                        Locale.getDefault(),
                        "%x",
                        renderer.activeVideoFormat
                    )
                }"
            )
            stringBuilder.appendLine("AVC Decoder: ${renderer.avcDecoder?.name ?: "(none)"}")
            stringBuilder.appendLine("HEVC Decoder: ${renderer.hevcDecoder?.name ?: "(none)"}")
            stringBuilder.appendLine("AV1 Decoder: ${renderer.av1Decoder?.name ?: "(none)"}")

            // Extract the decoder capabilities information into a separate function
            appendDecoderCapabilities(stringBuilder, renderer)

            stringBuilder.appendLine("Configured format: ${renderer.configuredFormat}")
            stringBuilder.appendLine("Input format: ${renderer.inputFormat}")
            stringBuilder.appendLine("Output format: ${renderer.outputFormat}")
            stringBuilder.appendLine("Adaptive playback: ${renderer.adaptivePlayback}")
            stringBuilder.appendLine("GL Renderer: ${renderer.glRenderer}")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                stringBuilder.appendLine("SOC: ${Build.SOC_MANUFACTURER} - ${Build.SOC_MODEL}")
                stringBuilder.appendLine("Performance class: ${Build.VERSION.MEDIA_PERFORMANCE_CLASS}")
            }

            stringBuilder.appendLine("Consecutive crashes: ${renderer.consecutiveCrashCount}")
            stringBuilder.appendLine("RFI active: ${renderer.refFrameInvalidationActive}")
            stringBuilder.appendLine("Using modern SPS patching: true")
            stringBuilder.appendLine("Fused IDR frames: ${renderer.fusedIdrFrame}")
            stringBuilder.appendLine("Video dimensions: ${renderer.initialWidth}x${renderer.initialHeight}")
            stringBuilder.appendLine("FPS target: ${renderer.refreshRate}")
            stringBuilder.appendLine("Bitrate: ${renderer.prefs.bitrate} Kbps")
            stringBuilder.appendLine("CSD stats: ${renderer.numVpsIn}, ${renderer.numSpsIn}, ${renderer.numPpsIn}")
            stringBuilder.appendLine("Frames in-out: ${renderer.numFramesIn}, ${renderer.numFramesOut}")
            stringBuilder.appendLine("Total frames received: ${renderer.globalVideoStats.totalFramesReceived}")
            stringBuilder.appendLine("Total frames rendered: ${renderer.globalVideoStats.totalFramesRendered}")
            stringBuilder.appendLine("Frame losses: ${renderer.globalVideoStats.framesLost} in ${renderer.globalVideoStats.frameLossEvents} loss events")
            stringBuilder.appendLine("Average end-to-end client latency: ${renderer.averageEndToEndLatency()}ms")
            stringBuilder.appendLine("Average hardware decoder latency: ${renderer.averageDecoderLatency()}ms")
            stringBuilder.appendLine("Frame pacing mode: ${renderer.prefs.framePacing}")

            if (originalException is MediaCodec.CodecException) {
                stringBuilder.appendLine("Diagnostic Info: ${originalException.diagnosticInfo}")
                stringBuilder.appendLine("Recoverable: ${originalException.isRecoverable}")
                stringBuilder.appendLine("Transient: ${originalException.isTransient}")
                stringBuilder.appendLine("Codec Error Code: ${originalException.errorCode}")
            }

            stringBuilder.appendLine(originalException.toString())

            return stringBuilder.toString()
        }

        // Helper function to append decoder capabilities information.
        private fun appendDecoderCapabilities(
            stringBuilder: StringBuilder,
            renderer: MediaCodecDecoderRenderer
        ) {
            renderer.avcDecoder?.let {
                val avcWidthRange =
                    it.getCapabilitiesForType("video/avc")?.videoCapabilities?.supportedWidths
                stringBuilder.appendLine("AVC supported width range: $avcWidthRange")
                stringBuilder.appendLine(
                    getAchievableFpsRange(
                        it,
                        "video/avc",
                        renderer.initialWidth,
                        renderer.initialHeight
                    )
                )
            }

            renderer.hevcDecoder?.let {
                val hevcWidthRange =
                    it.getCapabilitiesForType("video/hevc")?.videoCapabilities?.supportedWidths
                stringBuilder.appendLine("HEVC supported width range: $hevcWidthRange")
                stringBuilder.appendLine(
                    getAchievableFpsRange(
                        it,
                        "video/hevc",
                        renderer.initialWidth,
                        renderer.initialHeight
                    )
                )
            }

            renderer.av1Decoder?.let {
                val av1WidthRange =
                    it.getCapabilitiesForType("video/av01")?.videoCapabilities?.supportedWidths
                stringBuilder.appendLine("AV1 supported width range: $av1WidthRange")
                stringBuilder.appendLine(
                    getAchievableFpsRange(
                        it,
                        "video/av01",
                        renderer.initialWidth,
                        renderer.initialHeight
                    )
                )
            }
        }

        // Helper function to get achievable FPS range for a given decoder and video format.
        private fun getAchievableFpsRange(
            decoder: MediaCodecInfo,
            mimeType: String,
            width: Int,
            height: Int
        ): String {
            return try {
                val fpsRange =
                    decoder.getCapabilitiesForType(mimeType)?.videoCapabilities?.getAchievableFrameRatesFor(
                        width,
                        height
                    )
                "${mimeType.substringAfter('/')} achievable FPS range: $fpsRange"
            } catch (_: IllegalArgumentException) {
                "${mimeType.substringAfter('/')} achievable FPS range: UNSUPPORTED!"
            }
        }

        companion object {
            @Serial
            private val serialVersionUID = 8985937536997012406L
            const val DELIMITER = " | "

            const val PRE_SPS_ERROR = "PreSPSError"
            const val PRE_PPS_ERROR = "PrePPSError"
            const val PRE_IFRAME_ERROR = "PreIFrameError"
            const val PRE_OUTPUT_CONFIG_ERROR = "PreOutputConfigError"
            const val PRE_OUTPUT_ERROR = "PreOutputError"
            const val EARLY_OUTPUT_ERROR = "EarlyOutputError"
            const val ERROR_WHILE_STREAMING = "ErrorWhileStreaming"
        }
    }

    companion object {

        private var USE_FRAME_RENDER_TIME = false
        private var FRAME_RENDER_TIME_ONLY = false

        private var VIDEO_SR_UPSCALE_RATIO = 0f
        private const val CR_MAX_TRIES = 10
        private const val CR_RECOVERY_TYPE_NONE = 0
        private const val CR_RECOVERY_TYPE_FLUSH = 1
        private const val CR_RECOVERY_TYPE_RESTART = 2
        private const val CR_RECOVERY_TYPE_RESET = 3
        private const val RENDER_JOB_NAME = "Video - Renderer (MediaCodec)"
        private const val DEQUEUE_TIMEOUT_US = 50000L
        private const val MILLIS_PER_SECOND = 1000

        // Each thread that touches the MediaCodec object or any associated buffers must have a flag
        // here and must call doCodecRecoveryIfRequired() on a regular basis.
        private const val CR_FLAG_INPUT_THREAD = 0x1
        private const val CR_FLAG_RENDER_JOB = 0x2
        private const val CR_FLAG_CHOREOGRAPHER = 0x4
        private const val CR_FLAG_ALL =
            CR_FLAG_INPUT_THREAD or CR_FLAG_RENDER_JOB or CR_FLAG_CHOREOGRAPHER
        private const val EXCEPTION_REPORT_DELAY_MS = 3000
        private const val OUTPUT_BUFFER_QUEUE_LIMIT = 2

        private const val CREATE_CHOREOGRAPHER = 0
        private const val MSG_ADD_CALLBACK = 1
        private const val MSG_REMOVE_CALLBACK = 2
    }
}