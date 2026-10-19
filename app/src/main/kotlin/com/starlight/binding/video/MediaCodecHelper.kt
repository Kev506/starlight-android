@file:Suppress("unused")

package com.starlight.binding.video

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ConfigurationInfo
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.warning
import java.io.File
import java.util.Locale
import java.util.regex.Pattern


enum class MediaCodecHelper {
    ;

    companion object {

        private var preferredDecoders: List<String>? = null
        private var blacklistedDecoderPrefixes: MutableList<String>? = null
        private var spsFixupBitstreamFixupDecoderPrefixes: MutableList<String>? = null
        private var blacklistedAdaptivePlaybackPrefixes: MutableList<String>? = null
        private var baselineProfileHackPrefixes: MutableList<String>? = null
        private var directSubmitPrefixes: List<String>? = null
        private var constrainedHighProfilePrefixes: MutableList<String>? = null
        private var whitelistedHevcDecoders: MutableList<String>? = null
        private var refFrameInvalidationAvcPrefixes: MutableList<String>? = null
        private var refFrameInvalidationHevcPrefixes: MutableList<String>? = null
        private var useFourSlicesPrefixes: MutableList<String>? = null
        private var qualcommDecoderPrefixes: MutableList<String>? = null
        private var mtkDecoderPrefixes: MutableList<String>? = null
        private var kirinDecoderPrefixes: MutableList<String>? = null
        private var exynosDecoderPrefixes: MutableList<String>? = null
        private var amlogicDecoderPrefixes: MutableList<String>? = null
        private var knownVendorLowLatencyOptions: MutableList<String>? = null
        private val SHOULD_BYPASS_SOFTWARE_BLOCK =
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "cheets" || Build.BRAND == "Android-x86"

        private const val OPTIMAL_SLICES_PER_FRAME = 1
        private const val OPTIMAL_SLICES_FOR_OLDER_QUALCOMM = 4

        private var isLowEndSnapdragon = false
        private var isAdreno620 = false
        private var initialized = false
        private var isAmlogicS905X5Class = false

        init {

            // These decoders have low enough input buffer latency that they
            // can be directly invoked from the receive thread
            directSubmitPrefixes = listOf(
                "omx.qcom",
                "omx.sec",
                "omx.exynos",
                "omx.intel",
                "omx.brcm",
                "omx.TI",
                "omx.arc",
                "omx.nvidia",
                "c2."
            )

            refFrameInvalidationHevcPrefixes = mutableListOf()
            refFrameInvalidationAvcPrefixes = mutableListOf()
            blacklistedDecoderPrefixes = mutableListOf()
            spsFixupBitstreamFixupDecoderPrefixes = mutableListOf()
            baselineProfileHackPrefixes = mutableListOf()
            blacklistedAdaptivePlaybackPrefixes = mutableListOf()
            constrainedHighProfilePrefixes = mutableListOf()
            whitelistedHevcDecoders = mutableListOf()
            useFourSlicesPrefixes = mutableListOf()
            knownVendorLowLatencyOptions = mutableListOf()
            qualcommDecoderPrefixes = mutableListOf()
            kirinDecoderPrefixes = mutableListOf()
            mtkDecoderPrefixes = mutableListOf()
            exynosDecoderPrefixes = mutableListOf()
            amlogicDecoderPrefixes = mutableListOf()
            preferredDecoders = listOf()

            // Populate the list of prefixes for reference frame invalidation
            refFrameInvalidationHevcPrefixes!!.addAll(
                listOf(
                    "omx.exynos",
                    "c2.exynos"
                )
            )

            mtkDecoderPrefixes!!.addAll(
                listOf(
                    "omx.mtk",
                    "c2.mtk"
                )
            )

            // Blacklist software decoders that don't support H264 high profile except on systems
            // that are expected to only have software decoders (like emulators).
            if (!SHOULD_BYPASS_SOFTWARE_BLOCK) {
                blacklistedDecoderPrefixes!!.addAll(
                    listOf(
                        "omx.google",
                        "AVCDecoder"
                    )
                )

                // We want to avoid ffmpeg decoders since they're usually software decoders,
                // but we'll defer to the Android 10 isSoftwareOnly() API on newer devices
                // to determine if we should use these or not.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    blacklistedDecoderPrefixes!!.add("OMX.ffmpeg")
                }
            }

            // Force these decoders disabled because:
            // 1) They are software decoders, so the performance is terrible
            // 2) They crash with our HEVC stream anyway (at least prior to CSD batching)
            blacklistedDecoderPrefixes!!.addAll(
                listOf(
                    "OMX.qcom.video.decoder.hevcswvdec",
                    "OMX.SEC.hevc.sw.dec"
                )
            )

            // If a decoder qualifies for reference frame invalidation,
            // these entries will be ignored for those decoders.
            spsFixupBitstreamFixupDecoderPrefixes!!.addAll(
                listOf(
                    "c2.nvidia",
                    "c2.qti",
                    "c2.brcm"
                )
            )

            // Base line profile hack for some devices
            baselineProfileHackPrefixes!!.add("omx.intel")

            // The Intel decoder on Lollipop on Nexus Player would increase latency badly
            // if adaptive playback was enabled so let's avoid it to be safe.
            // The MediaTek decoder crashes at 1080p when adaptive playback is enabled
            // on some Android TV devices with HEVC only.
            blacklistedAdaptivePlaybackPrefixes!!.addAll(
                listOf(
                    "omx.intel",
                    "omx.mtk"
                )
            )

            // Constrain high profile for some devices
            constrainedHighProfilePrefixes!!.add("omx.intel")


            // Allow software HEVC decoding in the official AOSP emulator
            if (Build.HARDWARE == "ranchu") {
                whitelistedHevcDecoders!!.add("omx.google")
            }

            // Exynos seems to be the only HEVC decoder that works reliably
            whitelistedHevcDecoders!!.add("omx.exynos")

            // On Darcy (Shield 2017), HEVC runs fine with no fix-ups required. For some reason,
            // other X1 implementations require bitstream fix-ups. However, since numReferenceFrames
            // has been supported in GFE since late 2017, we'll go ahead and enable HEVC for all
            // device models.
            //
            // In case there are some that I missed, I will also exclude pre-Oreo OSes since
            // only Shield ATV got an Oreo update and any newer Tegra devices will not ship
            // with an old OS like Nougat.
            if (!Build.DEVICE.equals(
                    "shieldtablet",
                    ignoreCase = true
                ) && !Build.DEVICE.equals("mocha", ignoreCase = true)
            ) {
                whitelistedHevcDecoders!!.add("omx.nvidia")
            }

            // Plot twist: On newer Sony devices (BRAVIA_ATV2, BRAVIA_ATV3_4K, BRAVIA_UR1_4K) the H.264 decoder crashes
            // on several configurations (> 60 FPS and 1440p) that work with HEVC, so we'll whitelist those devices for HEVC.
            if (Build.DEVICE.startsWith("BRAVIA_")) {
                whitelistedHevcDecoders!!.add("omx.mtk")
            }

            // With the Android 12 update, Sabrina now uses HEVC (with RFI) based upon FEATURE_LowLatency
            // support, which provides equivalent latency to H.264 now.
            //
            if (!Build.DEVICE.equals("sabrina", ignoreCase = true)) {
                whitelistedHevcDecoders!!.add("omx.amlogic")
            }

            // Realtek SoCs are used inside many Android TV devices and can only do 4K60 with HEVC.
            // We'll enable those HEVC decoders by default and see if anything breaks.
            //
            // These theoretically have good HEVC decoding capabilities (potentially better than
            // their AVC decoders), but haven't been tested enough

            //whitelistedHevcDecoders.add("omx.rk"); // Unconfirmed

            // Add C2 to see if HEVC decoders are finally stable.
            whitelistedHevcDecoders!!.addAll(
                listOf(
                    "omx.realtek",
                    "c2."
                )
            )

            // Software decoders will use 4 slices per frame to allow for slice multithreading
            useFourSlicesPrefixes!!.addAll(
                listOf(
                    "omx.google",
                    "AVCDecoder",
                    "omx.ffmpeg",
                    "c2.android"
                )
            )

            // Vendor-specific low latency options
            knownVendorLowLatencyOptions!!.addAll(
                listOf(
                    "vendor.qti-ext-dec-low-latency.enable",
                    "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req",
                    "vendor.rtc-ext-dec-low-latency.enable",
                    "vendor.low-latency.enable",
                    "vendor.mtk.vdec.low-latency.strict.enable",
                    "vendor.mtk.vdec.early.output.enable",
                    "vendor.mtk.vdec.no.reorder.enable",
                    "vendor.mtk.vdec.disable.reorder",
                    "vendor.mtk.vdec.output.delay.frames",
                    "vendor.mtk.vdec.dpb.output.delay.frames",
                    "vendor.mtk.vdec.buffer.fetch.timeout.ms",
                    "vendor.mtk.vdec.no.clock.adjust"
                )
            )

            // QualCom decoder prefixes
            qualcommDecoderPrefixes!!.addAll(
                listOf(
                    "omx.qcom",
                    "c2.qti"
                )
            )

            // Kirin decoder prefixes
            kirinDecoderPrefixes!!.addAll(
                listOf(
                    "omx.hisi",
                    "c2.hisi" // Unconfirmed
                )
            )

            // Exynos decoder prefixes
            exynosDecoderPrefixes!!.addAll(
                listOf(
                    "omx.exynos",
                    "c2.exynos"
                )
            )

            // Amlogic decoder prefixes
            amlogicDecoderPrefixes!!.addAll(
                listOf(
                    "omx.amlogic",
                    "c2.amlogic" // Unconfirmed
                )
            )
        }

        // Check if the device has PowerVR
        @JvmStatic
        private fun isPowerVR(glRenderer: String): Boolean {
            return glRenderer.contains("powervr", ignoreCase = true)
        }

        // Get the Adreno GPU version string
        @JvmStatic
        private fun getAdrenoVersionString(glRenderers: String): String? {
            val lowerCaseRenderers = glRenderers.lowercase(Locale.getDefault()).trim()
            if (!lowerCaseRenderers.contains("adreno")) {
                return null
            }

            val modelNumberPattern = Pattern.compile("(.*)([0-9]{3})(.*)")
            val matcher = modelNumberPattern.matcher(lowerCaseRenderers)
            if (!matcher.matches()) {
                return null
            }

            val modelNumber = matcher.group(2)
            // Consider using a logging framework like Timber for better logging practices
            info("AdrenoGPU: Found Adreno GPU: $modelNumber")
            return modelNumber
        }

        // Check if the device has a low-end Snapdragon GPU
        @JvmStatic
        private fun isLowEndSnapdragonRenderer(glRenderer: String): Boolean {
            val modelNumber = getAdrenoVersionString(glRenderer)
                ?: // Not an Adreno GPU
                return false

            // The current logic is to identify low-end SoCs based on a zero in the x0x place.
            return modelNumber[1] == '0'
        }

        // Get the Adreno GPU model number
        @JvmStatic
        private fun getAdrenoRendererModelNumber(glRenderer: String): Int {
            val modelNumber = getAdrenoVersionString(glRenderer)
                ?: // Not an Adreno GPU
                return -1
            return modelNumber.toInt()
        }

        // Check if the device has a 400+ GPU
        // This is a workaround for some broken devices that report
        // only GLES 3.0 even though the GPU is an Adreno 4xx series part.
        // An example of such a device is the Huawei Honor 5x with the
        // Snapdragon 616 SoC (Adreno 405).
        @JvmStatic
        private fun isGLES31SnapdragonRenderer(glRenderer: String): Boolean {
            // Snapdragon 4xx and higher support GLES 3.1
            return getAdrenoRendererModelNumber(glRenderer) >= 400
        }

        // Initialize the MediaCodecHelper
        @JvmStatic
        fun initialize(context: Context, glRenderer: String) {
            if (initialized) {
                return
            }

            // Older Sony ATVs (SVP-DTV15) have broken MediaTek codecs (decoder hangs after rendering the first frame).
            // I know the Fire TV 2 and 3 works, so I'll whitelist Amazon devices which seem to actually be tested.
            // We still have to check Build.MANUFACTURER to catch Amazon Fire tablets.
            if (context.packageManager.hasSystemFeature("amazon.hardware.fire_tv") ||
                Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
            ) {
                // HEVC and RFI have been confirmed working on Fire TV 2, Fire TV Stick 2, Fire TV 4K Max,
                // Fire HD 8 2020, and Fire HD 8 2022 models.
                whitelistedHevcDecoders!!.addAll(
                    listOf(
                        "omx.mtk",
                        "omx.amlogic"
                    )
                )
                refFrameInvalidationHevcPrefixes!!.addAll(
                    listOf(
                        "omx.mtk",
                        "c2.mtk",
                        "omx.amlogic",
                        "c2.amlogic"
                    )
                )
            }
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val configInfo = activityManager.deviceConfigurationInfo
            if (configInfo.reqGlEsVersion != ConfigurationInfo.GL_ES_VERSION_UNDEFINED) {
                info("OpenGL ES version: " + configInfo.reqGlEsVersion)
                isLowEndSnapdragon = isLowEndSnapdragonRenderer(glRenderer)
                isAdreno620 = getAdrenoRendererModelNumber(glRenderer) == 620

                // Tegra K1 and later can do reference frame invalidation properly
                if (configInfo.reqGlEsVersion >= 0x30000) {
                    refFrameInvalidationAvcPrefixes!!.addAll(
                        listOf(
                            "omx.nvidia",
                            "c2.nvidia", // Unconfirmed
                            "omx.qcom",
                            "c2.qti",
                            "omx.mtk",
                            "c2.mtk"
                        )
                    )
                    info(
                        "Added omx.nvidia/c2.nvidia to reference frame invalidation support list"
                    )
                    info(
                        "Added omx.qcom/c2.qti to reference frame invalidation support list"
                    )

                    // Exclude HEVC RFI on Pixel C and Tegra devices prior to Android 11. Misbehaving RFI
                    // on these devices can cause hundreds of milliseconds of latency, so it's not worth
                    // using it unless we're absolutely sure that it will not cause increased latency.
                    if (!Build.DEVICE.equals(
                            "dragon",
                            ignoreCase = true
                        ) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ) {
                        refFrameInvalidationHevcPrefixes!!.add("omx.nvidia")
                    }

                    refFrameInvalidationHevcPrefixes!!.addAll(
                        listOf(
                            "c2.nvidia", // Unconfirmed
                            "omx.qcom",
                            "c2.qti",
                            "c2.mtk",
                            "omx.mtk"
                        )
                    )
                }

                // Qualcomm's early HEVC decoders break hard on our HEVC stream. The best check to
                // tell the good from the bad decoders are the generation of Adreno GPU included:
                // 3xx - bad
                // 4xx - good
                //
                // The "good" GPUs support GLES 3.1, but we can't just check that directly
                // (see comment on isGLES31SnapdragonRenderer).
                //
                if (isGLES31SnapdragonRenderer(glRenderer)) {
                    info(
                        "Added omx.qcom/c2.qti to HEVC decoders based on GLES 3.1+ support"
                    )
                    whitelistedHevcDecoders!!.addAll(
                        listOf(
                            "omx.qcom",
                            "c2.qti"
                        )
                    )
                } else {
                    blacklistedDecoderPrefixes!!.add("OMX.qcom.video.decoder.hevc")

                    // These older decoders need 4 slices per frame for best performance
                    useFourSlicesPrefixes!!.add("omx.qcom")
                }

                // Older MediaTek SoCs have issues with HEVC rendering but the newer chips with
                // PowerVR GPUs have good HEVC support.
                if (isPowerVR(glRenderer)) {
                    info("Added omx.mtk to HEVC decoders based on PowerVR GPU")
                    whitelistedHevcDecoders!!.add("omx.mtk")

                    // This SoC (MT8176 in GPD XD+) supports AVC RFI too, but the maxNumReferenceFrames setting
                    // required to make it work adds a huge amount of latency. However, RFI on HEVC causes
                    // decoder hangs on the newer GE8100, GE8300, and GE8320 GPUs, so we limit it to the
                    // Series6XT GPUs where we know it works.
                    if (glRenderer.contains("GX6")) {
                        info("Added omx.mtk/c2.mtk to RFI list for HEVC")
                        refFrameInvalidationHevcPrefixes!!.addAll(
                            listOf(
                                "omx.mtk",
                                "c2.mtk"
                            )
                        )
                    }
                }

                isAmlogicS905X5Class = isAmlogicS905X5ClassSoc(glRenderer)
                if (isAmlogicS905X5Class) {
                    info("Detected Amlogic S905X5-class SoC: HEVC will not use KEY_LOW_LATENCY")
                }
            }

            initialized = true
        }

        // Amlogic S905X5/S905X5M (Mali-G310) HEVC decoders advertise FEATURE_LowLatency, but with
        // KEY_LOW_LATENCY set they decode every frame and present only a few per second
        // (moonlight-android#1504). vdec-lowlatency plus vendor.low-latency.enable plays smoothly.
        // ro.soc.model carries a vendor prefix (Xiaomi TV Box S 3rd Gen: "AMLS905X5M"). The Mali-G310
        // check covers firmware that leaves it empty; it only takes effect together with an Amlogic
        // decoder name.
        private fun isAmlogicS905X5ClassSoc(glRenderer: String?): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    Build.SOC_MODEL.uppercase().contains("S905X5") || glRenderer != null && glRenderer.contains("Mali-G310")
        }

        private fun mustAvoidAndroidRLowLatency(
            decoderInfo: MediaCodecInfo,
            mimeType: String?
        ): Boolean {
            return isAmlogicS905X5Class &&
                    MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mimeType, ignoreCase = true) &&
                    isDecoderInList(amlogicDecoderPrefixes, decoderInfo.name)
        }

        // Check if a decoder is in the list
        @JvmStatic
        private fun isDecoderInList(decoderList: List<String>?, decoderName: String): Boolean {
            check(initialized) { "MediaCodecHelper must be initialized before use" }
            for (badPrefix in decoderList!!) {
                if (decoderName.length >= badPrefix.length) {
                    val prefix = decoderName.take(badPrefix.length)
                    if (prefix.equals(badPrefix, ignoreCase = true)) {
                        return true
                    }
                }
            }
            return false
        }

        // Check if a decoder supports Android R low latency
        @JvmStatic
        private fun decoderSupportsAndroidRLowLatency(
            decoderInfo: MediaCodecInfo,
            mimeType: String?
        ): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    if (decoderInfo.getCapabilitiesForType(mimeType)
                            .isFeatureSupported(CodecCapabilities.FEATURE_LowLatency)
                    ) {
                        info(
                            "Low latency decoding mode supported (FEATURE_LowLatency)"
                        )
                        return true
                    }
                } catch (e: Exception) {
                    // Tolerate buggy codecs
                    info("Low latency decoding mode not supported")
                }
            }
            return false
        }

        // Check if a decoder supports known vendor low latency options
        @JvmStatic
        private fun decoderSupportsKnownVendorLowLatencyOption(decoderName: String): Boolean {
            // It's only possible to probe vendor parameters on Android 12 and above.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                var testCodec: MediaCodec? = null
                try {
                    // Unfortunately we have to create an actual codec instance to get supported options.
                    testCodec = MediaCodec.createByCodecName(decoderName)

                    // See if any of the vendor parameters match ones we know about
                    for (supportedOption in testCodec.supportedVendorParameters) {
                        for (knownLowLatencyOption in knownVendorLowLatencyOptions!!) {
                            if (supportedOption.equals(knownLowLatencyOption, ignoreCase = true)) {
                                info(
                                    "$decoderName supports known low latency option: $supportedOption"
                                )
                                return true
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Tolerate buggy codecs
                    info("Low latency decoding mode not supported")
                } finally {
                    testCodec?.release()
                }
            }
            return false
        }

        // Check if a decoder supports operating at maximum rate
        @JvmStatic
        private fun decoderSupportsMaxOperatingRate(decoderName: String): Boolean {
            // Operate at maximum rate to lower latency as much as possible on
            // some Qualcomm platforms. We could also set KEY_PRIORITY to 0 (realtime)
            // but that will actually result in the decoder crashing if it can't satisfy
            // our (ludicrous) operating rate requirement. This seems to cause reliable
            // crashes on the Xiaomi Mi 10 lite 5G and Redmi K30i 5G on Android 10, so
            // we'll disable it on Snapdragon 765G and all non-Qualcomm devices to be safe.
            //
            // NB: Even on Android 10, this optimization still provides significant
            // performance gains on Pixel 2.
            return isDecoderInList(qualcommDecoderPrefixes, decoderName) &&
                    isDecoderInList(refFrameInvalidationHevcPrefixes, decoderName) &&
                    isDecoderInList(refFrameInvalidationAvcPrefixes, decoderName) &&
                    !isAdreno620
        }

        // Set the decoder low latency options
        @JvmStatic
        fun setDecoderLowLatencyOptions(
            videoFormat: MediaFormat, decoderInfo: MediaCodecInfo,
            tryNumber: Int
        ): Boolean {

            // Options here should be tried in the order of most to least risky. The decoder will use
            // the first MediaFormat that doesn't fail in configure().
            var setNewOption = false

            if (tryNumber == 0) {
                // Official Android 11+ low latency option (KEY_LOW_LATENCY).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    videoFormat.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    setNewOption = true
                }

                // If this decoder officially supports FEATURE_LowLatency, we will just use that alone
                // for try 0. Otherwise, we'll include it as best effort with other options.
                if (decoderSupportsAndroidRLowLatency(
                        decoderInfo,
                        videoFormat.getString(MediaFormat.KEY_MIME)
                    )
                ) {
                    setNewOption = true
                }
            } else if (tryNumber == 1 && mustAvoidAndroidRLowLatency(
                    decoderInfo,
                    videoFormat.getString(MediaFormat.KEY_MIME)
                )
            ) {
                // Fall through to vdec-lowlatency and the Amlogic vendor extension below.
                info("Skipping KEY_LOW_LATENCY for Amlogic HEVC decoder")
            } else if (tryNumber == 1) {
                // MediaTek, Amazon Amlogic, and some Sony Bravia devices support "vdec-lowlatency"
                videoFormat.setInteger("vdec-lowlatency", 1)
                setNewOption = true
            } else if (tryNumber == 2) {
                if (decoderSupportsMaxOperatingRate(decoderInfo.name)) {
                    videoFormat.setInteger(
                        MediaFormat.KEY_OPERATING_RATE,
                        Short.MAX_VALUE.toInt()
                    )
                } else {
                    videoFormat.setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
                setNewOption = true
            }

            // Handle vendor-specific options based on decoder prefix
            else if (tryNumber == 3) {
                when {
                    isDecoderInList(qualcommDecoderPrefixes, decoderInfo.name) -> {
                        videoFormat.setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
                        setNewOption = true
                    }

                    isDecoderInList(kirinDecoderPrefixes, decoderInfo.name) -> {
                        videoFormat.setInteger(
                            "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req",
                            1
                        )
                        videoFormat.setInteger(
                            "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy",
                            -1
                        )
                        setNewOption = true
                    }

                    isDecoderInList(exynosDecoderPrefixes, decoderInfo.name) -> {
                        videoFormat.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
                        setNewOption = true
                    }

                    isDecoderInList(amlogicDecoderPrefixes, decoderInfo.name) -> {
                        videoFormat.setInteger("vendor.low-latency.enable", 1)
                        setNewOption = true
                    }
                }
            } else if (tryNumber == 4) {
                when {
                    isDecoderInList(qualcommDecoderPrefixes, decoderInfo.name) -> {
                        videoFormat.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)

                        setNewOption = true
                    }

                    isDecoderInList(mtkDecoderPrefixes, decoderInfo.name) -> {
                        videoFormat.setInteger("vendor.mtk.vdec.cpu.boost.mode", 1)
                        videoFormat.setInteger("vendor.mtk.ext.dolby.vision.cpu-boost", 1)
                        videoFormat.setInteger("vendor.mtk.vdec.bq.guard.interval.time", 4)
                        videoFormat.setInteger("vendor.mtk.vdec.buffer.fetch.timeout.ms", 4)
                        videoFormat.setInteger("vdec-lowlatency", 1)
                        videoFormat.setInteger("vendor.mtk.vdec.dvfs.mode", 1)
                        videoFormat.setInteger("vendor.mtk.vdec.dvfs.level", 1)
                        videoFormat.setInteger("vendor.mtk.vdec.low-latency.mode", 1)
                        videoFormat.setInteger("vendor.mtk.vdec.ultra-low-latency", 0)
                        videoFormat.setInteger("vendor.mtk.vdec.disable-idle", 1)
                        videoFormat.setInteger("vendor.mtk.vdec.preload.frame.count", 1)
                        videoFormat.setInteger("vendor.mtk.vdec.input.max.queue.depth", 3)
                        videoFormat.setInteger("vendor.mtk.vdec.output.max.queue.depth", 3)
                        videoFormat.setInteger("vendor.mtk.vdec.vsync.adjust.enable", 0)

                        // Skip/drop: only NVOP
                        videoFormat.setInteger("vendor.mtk.vdec.nvop.skip", 1)
                        videoFormat.setInteger("vendor.mtk.vdec.skip.mode", 0)
                        videoFormat.setInteger("vendor.mtk.vdec.drop.nonref.frame", 0)
                        videoFormat.setInteger("vendor.mtk.vdec.frame-drop.policy", 0)

                        try {
                            videoFormat.setInteger(
                                MediaFormat.KEY_OPERATING_RATE,
                                Short.MAX_VALUE.toInt()
                            )
                        } catch (ignored: Throwable) {
                        }

                        try {
                            videoFormat.setInteger(MediaFormat.KEY_PRIORITY, 0)
                        } catch (ignored: Throwable) {
                        }

                        setNewOption = true
                    }
                }
            }
            return setNewOption
        }

        // Check if a decoder supports fused IDR frames
        @JvmStatic
        fun decoderSupportsFusedIdrFrame(decoderInfo: MediaCodecInfo, mimeType: String?): Boolean {
            return try {
                decoderInfo.getCapabilitiesForType(mimeType)
                    ?.isFeatureSupported(CodecCapabilities.FEATURE_AdaptivePlayback) == true
            } catch (e: Exception) {
                info("Decoder does not support fused IDR frames due to exception: ${e.message}")
                false
            }
        }

        // Check if a decoder supports adaptive playback
        @JvmStatic
        fun decoderSupportsAdaptivePlayback(
            decoderInfo: MediaCodecInfo,
            mimeType: String?
        ): Boolean {

            if (isDecoderInList(blacklistedAdaptivePlaybackPrefixes, decoderInfo.name)) {
                info("Decoder blacklisted for adaptive playback")
                return false
            }

            return try {
                decoderInfo.getCapabilitiesForType(mimeType)
                    ?.isFeatureSupported(CodecCapabilities.FEATURE_AdaptivePlayback) == true
            } catch (e: Exception) {
                info(
                    "Decoder does not support adaptive playback due to exception: ${e.message}"
                )
                false
            }
        }

        // Check if a decoder needs constrained high profile
        @JvmStatic
        fun decoderNeedsConstrainedHighProfile(decoderName: String): Boolean {
            return isDecoderInList(constrainedHighProfilePrefixes, decoderName)
        }

        // Check if a decoder can submit direct frames
        @JvmStatic
        fun decoderCanDirectSubmit(decoderName: String): Boolean {
            return isDecoderInList(directSubmitPrefixes, decoderName) && !isExynos4Device()
        }

        // Check if a decoder needs SPS bitstream restrictions
        @JvmStatic
        fun decoderNeedsSpsBitstreamRestrictions(decoderName: String): Boolean {
            return isDecoderInList(spsFixupBitstreamFixupDecoderPrefixes, decoderName)
        }

        // Check if a decoder needs baseline profile
        @JvmStatic
        fun decoderNeedsBaselineSpsHack(decoderName: String): Boolean {
            return isDecoderInList(baselineProfileHackPrefixes, decoderName)
        }

        // Get the optimal number of slices per frame for a decoder
        @JvmStatic
        fun getDecoderOptimalSlicesPerFrame(decoderName: String): Byte {
            return if (isDecoderInList(
                    useFourSlicesPrefixes,
                    decoderName
                )
            ) {
                // 4 slices per frame reduces decoding latency on older Qualcomm devices
                OPTIMAL_SLICES_FOR_OLDER_QUALCOMM.toByte()
            } else {
                // 1 slice per frame produces the optimal encoding efficiency
                OPTIMAL_SLICES_PER_FRAME.toByte()
            }
        }

        // Check if a decoder supports reference frame invalidation
        @JvmStatic
        fun decoderSupportsRefFrameInvalidationAvc(codecInfo: MediaCodecInfo): Boolean {
            val mime = "video/av01"
            try {
                if (decoderSupportsAndroidRLowLatency(codecInfo, mime) ||
                    decoderSupportsKnownVendorLowLatencyOption(codecInfo.name) ||
                    decoderSupportsAdaptivePlayback(codecInfo, mime)
                ) {
                    info("Enabling AV1 RFI (low-latency/adaptive/vendor ok)")
                    return true
                }
            } catch (t: Throwable) {
                try {
                    t.printStackTrace()
                } catch (ignored: Throwable) {
                }
            }
            return false
        }

        // Check if a decoder supports reference frame invalidation
        @JvmStatic
        fun decoderSupportsRefFrameInvalidationHevc(decoderInfo: MediaCodecInfo): Boolean {
            // HEVC decoders seem to universally support RFI, but it can have huge latency penalties
            // for some decoders due to the number of references frames being > 1. Old Amlogic
            // decoders are known to have this problem.
            //
            // If the decoder supports FEATURE_LowLatency or any vendor low latency option,
            // we will use that as an indication that it can handle HEVC RFI without excessively
            // buffering frames.
            if (decoderSupportsLowLatencyHevc(decoderInfo)) {
                info("Enabling HEVC RFI based on low latency option support")
                return true
            }
            return isDecoderInList(refFrameInvalidationHevcPrefixes, decoderInfo.name)
        }

        // Check if a decoder supports low latency HEVC decoding
        private fun decoderSupportsLowLatencyHevc(decoderInfo: MediaCodecInfo): Boolean {
            return decoderSupportsAndroidRLowLatency(decoderInfo, "video/hevc") ||
                    decoderSupportsKnownVendorLowLatencyOption(decoderInfo.name)
        }

        // Check if a decoder supports reference frame invalidation
        @JvmStatic
        fun decoderSupportsRefFrameInvalidationAv1(decoderInfo: MediaCodecInfo): Boolean {
            // We'll use the same heuristics as HEVC for now
            if (decoderSupportsLowLatencyAv1(decoderInfo)) {
                info("Enabling AV1 RFI based on low latency option support")
                return true
            }
            return false
        }

        // Check if a decoder supports low latency AV1 decoding
        private fun decoderSupportsLowLatencyAv1(decoderInfo: MediaCodecInfo): Boolean {
            return decoderSupportsAndroidRLowLatency(decoderInfo, "video/av01") ||
                    decoderSupportsKnownVendorLowLatencyOption(decoderInfo.name)
        }

        // Check if a decoder is whitelisted for HEVC decoding
        @JvmStatic
        fun decoderIsWhitelistedForHevc(decoderInfo: MediaCodecInfo): Boolean {
            if (decoderInfo.name.contains("sw") ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !decoderInfo.isHardwareAccelerated)
            ) {
                info("Disallowing HEVC on software decoder: ${decoderInfo.name}")
                return false
            }

            // If this device is media performance class 12 or higher, we will assume any hardware
            // HEVC decoder present is fast and modern enough for streaming.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                Build.VERSION.MEDIA_PERFORMANCE_CLASS >= Build.VERSION_CODES.S
            ) {
                info("Allowing HEVC based on media performance class")
                return true
            }

            // Check for LowLatency support
            if (decoderSupportsAndroidRLowLatency(decoderInfo, "video/hevc")) {
                info("Allowing HEVC based on FEATURE_LowLatency support")
                return true
            }

            // Fallback to whitelist check.
            return isDecoderInList(whitelistedHevcDecoders, decoderInfo.name)
        }

        // Check if a decoder is whitelisted for AV1 decoding
        @JvmStatic
        fun isDecoderWhitelistedForAv1(decoderInfo: MediaCodecInfo): Boolean {
            // Google didn't have official support forAV1 (or more importantly, a CTS test) until
            // Android 10, so don't use any decoder before then.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return false
            }

            // Allow AV1 decoding only on hardware-accelerated decoders.
            if (!decoderInfo.isHardwareAccelerated || decoderInfo.isSoftwareOnly) {
                info("Disallowing AV1 on software decoder: ${decoderInfo.name}")
                return false
            }

            // We know at this point, the decoder is hardware-accelerated.
            return true // Allow AV1 decoding on this hardware decoder
        }

        // Get a list of all available decoders
        @JvmStatic
        private fun mediaCodecList(): List<MediaCodecInfo> {
            return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
        }

        // Dump decoders
        @JvmStatic
        fun dumpDecoders(): String {
            val stringBuilder = StringBuilder()
            for (codecInfo in mediaCodecList()) {
                //Skip encoders
                if (codecInfo.isEncoder) {
                    continue
                }
                stringBuilder.appendLine("Decoder: ${codecInfo.name}")
                for (type in codecInfo.supportedTypes) {
                    stringBuilder.appendLine("\t$type")
                    val capabilities = codecInfo.getCapabilitiesForType(type)
                    for (profile in capabilities.profileLevels) {
                        stringBuilder.appendLine("\t\t${profile.profile} ${profile.level}")
                    }
                }
            }
            return stringBuilder.toString()
        }

        // Get the preferred decoder
        @JvmStatic
        private fun findPreferredDecoder(): MediaCodecInfo? {
            // This is a different algorithm than the other findXXXDecoder functions,
            // because we want to evaluate the decoders in our list's order
            // rather than MediaCodecList's order
            check(initialized) { "MediaCodecHelper must be initialized before use" }

            // Early return if no preferred decoders are specified
            val preferredDecoders = preferredDecoders ?: return null

            for (preferredDecoder in preferredDecoders) {
                for (codecInfo in mediaCodecList()) {
                    // Skip encoders
                    if (codecInfo.isEncoder) continue

                    // Check for preferred decoders, using equalsIgnoreCase for more robust comparison
                    if (codecInfo.name.equals(preferredDecoder, ignoreCase = true)) {
                        info("Preferred decoder choice is ${codecInfo.name}")
                        return codecInfo
                    }
                }
            }
            return null
        }

        // Check if a decoder is blacklisted
        @JvmStatic
        private fun isCodecBlacklisted(codecInfo: MediaCodecInfo): Boolean {

            // Check for explicitly blacklisted decoders first for efficiency
            if (isDecoderInList(blacklistedDecoderPrefixes, codecInfo.name)) {
                info("Skipping blacklisted decoder: " + codecInfo.name)
                return true
            }

            // Use the new isSoftwareOnly() function on Android Q and above
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !SHOULD_BYPASS_SOFTWARE_BLOCK && codecInfo.isSoftwareOnly) {
                info("Skipping software-only decoder: " + codecInfo.name)
                return true
            }

            return false
        }

        // Get the first decoder
        @JvmStatic
        fun findFirstDecoder(mimeType: String?): MediaCodecInfo? {
            for (codecInfo in mediaCodecList()) {
                //Skip encoders and compatibility aliases
                if (codecInfo.isEncoder || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && codecInfo.isAlias)) {
                    continue
                }

                // Check if codec supports the mime type
                if (codecInfo.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) {
                    // Skip blacklisted codecs
                    if (isCodecBlacklisted(codecInfo)) {
                        continue
                    }
                    info("First decoder choice is ${codecInfo.name}")
                    return codecInfo
                }
            }
            return null
        }

        // Get the probable safe decoder
        @JvmStatic
        fun findProbableSafeDecoder(mimeType: String, requiredProfile: Int): MediaCodecInfo? {

            // First look for a preferred decoder by name
            findPreferredDecoder()?.let { return it }

            return try {
                // Then look for a known safe decoder
                findKnownSafeDecoder(mimeType, requiredProfile)
            } catch (e: Exception) {
                // Some buggy devices throw exceptions from getCapabilitiesForType()
                // so we'll just assume they're okay and go with the first one we find
                findFirstDecoder(mimeType)
            }
        }

        // Get the known safe decoder
        @JvmStatic
        @Throws(Exception::class)
        private fun findKnownSafeDecoder(mimeType: String, requiredProfile: Int): MediaCodecInfo? {
            // Some devices (Exynos devices, at least) have two sets of decoders.
            // The first set of decoders are C2 which do not support FEATURE_LowLatency,
            // but the second set of OMX decoders do support FEATURE_LowLatency. We want
            // to pick the OMX decoders despite the fact that C2 is listed first.
            // On some Qualcomm devices (like Pixel 4), there are separate low latency decoders
            // (like c2.qti.hevc.decoder.low_latency) that advertise FEATURE_LowLatency while
            // the standard ones (like c2.qti.hevc.decoder) do not. Like Exynos, the decoders
            // with FEATURE_LowLatency support are listed after the standard ones.
            for (i in 0..1) {
                for (codecInfo in mediaCodecList()) {
                    // Skip encoders
                    if (codecInfo.isEncoder) {
                        continue
                    }

                    // Skip compatibility aliases on Q+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (codecInfo.isAlias) {
                            continue
                        }
                    }

                    // Find a decoder that supports the requested video format
                    for (mime in codecInfo.supportedTypes) {
                        if (mime.equals(mimeType, ignoreCase = true)) {
                            info(
                                "Examining decoder capabilities of " + codecInfo.name + " (round " + (i + 1) + ")"
                            )

                            // Skip blacklisted codecs
                            if (isCodecBlacklisted(codecInfo)) {
                                continue
                            }
                            val caps = codecInfo.getCapabilitiesForType(mime)
                            if (i == 0 && !decoderSupportsAndroidRLowLatency(codecInfo, mime)) {
                                info(
                                    "Skipping decoder that lacks FEATURE_LowLatency for round 1"
                                )
                                continue
                            }
                            if (requiredProfile != -1) {
                                for (profile in caps.profileLevels) {
                                    if (profile.profile == requiredProfile) {
                                        info(
                                            "Decoder " + codecInfo.name + " supports required profile"
                                        )
                                        return codecInfo
                                    }
                                }
                                info(
                                    "Decoder " + codecInfo.name + " does NOT support required profile"
                                )
                            } else {
                                return codecInfo
                            }
                        }
                    }
                }
            }
            return null
        }

        // Read CPU info
        @JvmStatic
        @Throws(Exception::class)
        fun readCpuinfo(): String {
            val cpuInfo = StringBuilder()
            File("/proc/cpuinfo").useLines { lines ->
                lines.forEach { line ->
                    cpuInfo.appendLine(line)
                }
            }
            return cpuInfo.toString()
        }

        // Check if a string contains an ignore case substring
        @JvmStatic
        private fun stringContainsIgnoreCase(string: String, substring: String): Boolean {
            return string.lowercase().contains(substring.lowercase())
        }

        // Check if the device is an Exynos 4 device
        @JvmStatic
        fun isExynos4Device(): Boolean {
            // Check CPU info first
            try {
                val cpuInfo = readCpuinfo()
                if (cpuInfo.contains("SMDK4", ignoreCase = true)) {
                    info("Found SMDK4 in /proc/cpuinfo")
                    return true
                }
                if (cpuInfo.contains("Exynos 4", ignoreCase = true)) {
                    info("Found Exynos 4 in /proc/cpuinfo")
                    return true
                }
            } catch (e: Exception) {
                warning("Failed to read /proc/cpuinfo")
            }

            // Check system directory if CPU info doesn't yield results
            try {
                val systemDir = File("/sys/devices/system")
                systemDir.listFiles()?.forEach { file ->
                    if (file.name.contains("exynos4", ignoreCase = true)) {
                        info("Found exynos4 in /sys/devices/system")
                        return true
                    }
                }
            } catch (e: Exception) {
                warning("Failed to read /sys/devices/system")
            }

            return false
        }
    }
}