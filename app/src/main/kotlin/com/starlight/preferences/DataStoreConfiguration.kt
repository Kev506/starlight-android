package com.starlight.preferences

import android.content.Context
import android.os.Build
import android.util.Size
import android.view.Display
import androidx.core.hardware.display.DisplayManagerCompat
import androidx.core.view.DisplayCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.starlight.binding.audio.AudioOutputMode
import com.starlight.nvstream.http.NvApp
import com.starlight.nvstream.jni.StarBridge
import com.starlight.utils.Constants.AAUDIO_PREF_STRING
import com.starlight.utils.Constants.ABSOLUTE_MOUSE_MODE_PREF_STRING
import com.starlight.utils.Constants.ANALOG_SCROLLING_PREF_STRING
import com.starlight.utils.Constants.APP_VIEW_KEY_SELECTED_POSITION
import com.starlight.utils.Constants.AUDIO_CONFIG_PREF_STRING
import com.starlight.utils.Constants.AUDIO_OUTPUT_MODE_PREF_STRING
import com.starlight.utils.Constants.BIND_ALL_USB_STRING
import com.starlight.utils.Constants.BITRATE_PREF_STRING
import com.starlight.utils.Constants.BROKEN_HDR_FIRMWARE_FINGERPRINT
import com.starlight.utils.Constants.DATABASE_PREF
import com.starlight.utils.Constants.DEADZONE_PREF_STRING
import com.starlight.utils.Constants.DEFAULT_AAUDIO
import com.starlight.utils.Constants.DEFAULT_ABSOLUTE_MOUSE_MODE
import com.starlight.utils.Constants.DEFAULT_ANALOG_STICK_FOR_SCROLLING
import com.starlight.utils.Constants.DEFAULT_AUDIO_CONFIG
import com.starlight.utils.Constants.DEFAULT_AUDIO_MODE
import com.starlight.utils.Constants.DEFAULT_BIND_ALL_USB
import com.starlight.utils.Constants.DEFAULT_CAPTIONS
import com.starlight.utils.Constants.DEFAULT_CAPTIONS_STRING
import com.starlight.utils.Constants.DEFAULT_DATABASE_ENABLED
import com.starlight.utils.Constants.DEFAULT_DEADZONE
import com.starlight.utils.Constants.DEFAULT_DISABLE_TOASTS
import com.starlight.utils.Constants.DEFAULT_ENABLE_AUDIO_FX
import com.starlight.utils.Constants.DEFAULT_ENABLE_HDR
import com.starlight.utils.Constants.DEFAULT_ENABLE_PERF_OVERLAY
import com.starlight.utils.Constants.DEFAULT_ENABLE_PIP
import com.starlight.utils.Constants.DEFAULT_FINGERPRINT_PREF
import com.starlight.utils.Constants.DEFAULT_FLIP_FACE_BUTTONS
import com.starlight.utils.Constants.DEFAULT_FPS
import com.starlight.utils.Constants.DEFAULT_FRAME_PACING
import com.starlight.utils.Constants.DEFAULT_FULL_RANGE
import com.starlight.utils.Constants.DEFAULT_GAMEPAD_MOTION_FALLBACK
import com.starlight.utils.Constants.DEFAULT_GAMEPAD_MOTION_SENSORS
import com.starlight.utils.Constants.DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE
import com.starlight.utils.Constants.DEFAULT_HEADER_PREF
import com.starlight.utils.Constants.DEFAULT_HOST_AUDIO
import com.starlight.utils.Constants.DEFAULT_LANGUAGE
import com.starlight.utils.Constants.DEFAULT_LANGUAGE_STRING
import com.starlight.utils.Constants.DEFAULT_LATENCY_TOAST
import com.starlight.utils.Constants.DEFAULT_ML_DOWNLOADED
import com.starlight.utils.Constants.DEFAULT_MOUSE_EMULATION
import com.starlight.utils.Constants.DEFAULT_MOUSE_NAV_BUTTONS
import com.starlight.utils.Constants.DEFAULT_MULTI_CONTROLLER
import com.starlight.utils.Constants.DEFAULT_OPACITY
import com.starlight.utils.Constants.DEFAULT_REDUCE_REFRESH_RATE
import com.starlight.utils.Constants.DEFAULT_RENDERER_PREF
import com.starlight.utils.Constants.DEFAULT_RESOLUTION
import com.starlight.utils.Constants.DEFAULT_STRETCH
import com.starlight.utils.Constants.DEFAULT_TOUCHSCREEN_TRACKPAD
import com.starlight.utils.Constants.DEFAULT_UNLOCK_FPS
import com.starlight.utils.Constants.DEFAULT_USB_DRIVER
import com.starlight.utils.Constants.DEFAULT_VIBRATE_FALLBACK
import com.starlight.utils.Constants.DEFAULT_VIBRATE_FALLBACK_STRENGTH
import com.starlight.utils.Constants.DEFAULT_VIBRATE_OSC
import com.starlight.utils.Constants.DEFAULT_VIDEO_FORMAT
import com.starlight.utils.Constants.DEFAULT_VIDEO_SR
import com.starlight.utils.Constants.DISABLE_TOASTS_PREF_STRING
import com.starlight.utils.Constants.ENABLE_AUDIO_FX_PREF_STRING
import com.starlight.utils.Constants.ENABLE_HDR_PREF_STRING
import com.starlight.utils.Constants.ENABLE_PERF_OVERLAY_STRING
import com.starlight.utils.Constants.ENABLE_PIP_PREF_STRING
import com.starlight.utils.Constants.FAVORITES_DEFAULT_PREF
import com.starlight.utils.Constants.FAVORITES_PREF
import com.starlight.utils.Constants.FINGERPRINT_PREF
import com.starlight.utils.Constants.FLIP_FACE_BUTTONS_PREF_STRING
import com.starlight.utils.Constants.FPS_PREF_STRING
import com.starlight.utils.Constants.FRAME_PACING_BALANCED
import com.starlight.utils.Constants.FRAME_PACING_CAP_FPS
import com.starlight.utils.Constants.FRAME_PACING_MAX_SMOOTHNESS
import com.starlight.utils.Constants.FRAME_PACING_MIN_LATENCY
import com.starlight.utils.Constants.FRAME_PACING_PREF_STRING
import com.starlight.utils.Constants.FULL_RANGE_PREF_STRING
import com.starlight.utils.Constants.GAMEPAD_MOTION_FALLBACK_PREF_STRING
import com.starlight.utils.Constants.GAMEPAD_MOTION_SENSORS_PREF_STRING
import com.starlight.utils.Constants.GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING
import com.starlight.utils.Constants.GLES_PREF_STRING
import com.starlight.utils.Constants.HEADER_PREF
import com.starlight.utils.Constants.HOST_AUDIO_PREF_STRING
import com.starlight.utils.Constants.KEY_FAV_SELECTED_POSITION
import com.starlight.utils.Constants.KEY_SELECTED_POSITION
import com.starlight.utils.Constants.LANGUAGE_PREF_STRING
import com.starlight.utils.Constants.LATENCY_TOAST_PREF_STRING
import com.starlight.utils.Constants.LEANBACK_PREF_STRING
import com.starlight.utils.Constants.LEAN_BACK_DEFAULT
import com.starlight.utils.Constants.ML_DOWNLOADED
import com.starlight.utils.Constants.MOUSE_EMULATION_STRING
import com.starlight.utils.Constants.MOUSE_NAV_BUTTONS_STRING
import com.starlight.utils.Constants.MULTI_CONTROLLER_PREF_STRING
import com.starlight.utils.Constants.NVIDIA_MANUFACTURER
import com.starlight.utils.Constants.ONLY_L3_R3_DEFAULT
import com.starlight.utils.Constants.ONLY_L3_R3_PREF_STRING
import com.starlight.utils.Constants.ONSCREEN_CONTROLLER_DEFAULT
import com.starlight.utils.Constants.ONSCREEN_CONTROLLER_PREF_STRING
import com.starlight.utils.Constants.OSC_OPACITY_PREF_STRING
import com.starlight.utils.Constants.PREF_KEY_CRASH_COUNT
import com.starlight.utils.Constants.PREF_KEY_LAST_NOTIFIED_CRASH_COUNT
import com.starlight.utils.Constants.REDUCE_REFRESH_RATE_PREF_STRING
import com.starlight.utils.Constants.RENDERER_PREF
import com.starlight.utils.Constants.RESOLUTION_PREF_STRING
import com.starlight.utils.Constants.SMALL_ICONS_DEFAULT
import com.starlight.utils.Constants.SMALL_ICONS_PREF_STRING
import com.starlight.utils.Constants.SQUARISH_SCREEN_ASPECT_RATIO_THRESHOLD
import com.starlight.utils.Constants.STANDARD_RESOLUTION
import com.starlight.utils.Constants.STATS_ENABLED
import com.starlight.utils.Constants.STATS_PREF_STRING
import com.starlight.utils.Constants.STRETCH_PREF_STRING
import com.starlight.utils.Constants.SUN_FAVORITES_DEFAULT_PREF
import com.starlight.utils.Constants.SUN_FAVORITES_PREF
import com.starlight.utils.Constants.TOUCHSCREEN_TRACKPAD_PREF_STRING
import com.starlight.utils.Constants.UNLOCK_FPS_STRING
import com.starlight.utils.Constants.USB_DRIVER_PREF_SRING
import com.starlight.utils.Constants.VIBRATE_FALLBACK_PREF_STRING
import com.starlight.utils.Constants.VIBRATE_FALLBACK_STRENGTH_PREF_STRING
import com.starlight.utils.Constants.VIBRATE_OSC_PREF_STRING
import com.starlight.utils.Constants.VIDEO_FORMAT_PREF_STRING
import com.starlight.utils.Constants.VIDEO_SELECTED_POSITION
import com.starlight.utils.Constants.VIDEO_SR_STRING
import com.starlight.utils.LimeLog.warning
import com.starlight.utils.Starlight.Companion.applicationScope
import com.starlight.utils.Starlight.Companion.dataStore
import com.starlight.utils.Starlight.Companion.instance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt


/**
 * Created by Kevin Andrews on 22/06/2026.
 */

enum class FormatOption {
    AUTO,
    FORCE_AV1,
    FORCE_HEVC,
    FORCE_H264
}

enum class AnalogStickForScrolling {
    NONE,
    RIGHT,
    LEFT
}

data class DataStoreConfiguration(
    val resolution: String = DEFAULT_RESOLUTION,
    val language: String = DEFAULT_LANGUAGE,
    val stretchVideo: Boolean = DEFAULT_STRETCH,
    val playHostAudio: Boolean = DEFAULT_HOST_AUDIO,
    val disableWarnings: Boolean = DEFAULT_DISABLE_TOASTS,
    val leanback: Boolean = LEAN_BACK_DEFAULT,
    val enableHdr: Boolean = DEFAULT_ENABLE_HDR,
    val smallIconMode: Boolean = SMALL_ICONS_DEFAULT,
    val multiController: Boolean = DEFAULT_MULTI_CONTROLLER,
    val usbDriver: Boolean = DEFAULT_USB_DRIVER,
    val flipFaceButtons: Boolean = DEFAULT_FLIP_FACE_BUTTONS,
    val onscreenController: Boolean = ONSCREEN_CONTROLLER_DEFAULT,
    val onlyL3R3: Boolean = ONLY_L3_R3_DEFAULT,
    val enablePip: Boolean = DEFAULT_ENABLE_PIP,
    val enablePerfOverlay: Boolean = DEFAULT_ENABLE_PERF_OVERLAY,
    val enableLatencyToast: Boolean = DEFAULT_LATENCY_TOAST,
    val bindAllUsb: Boolean = DEFAULT_BIND_ALL_USB,
    val mouseEmulation: Boolean = DEFAULT_MOUSE_EMULATION,
    val absoluteMouseMode: Boolean = DEFAULT_ABSOLUTE_MOUSE_MODE,
    val enableAudioFx: Boolean = DEFAULT_ENABLE_AUDIO_FX,
    val reduceRefreshRate: Boolean = DEFAULT_REDUCE_REFRESH_RATE,
    val fullRange: Boolean = DEFAULT_FULL_RANGE,
    val gamepadMotionSensors: Boolean = DEFAULT_GAMEPAD_MOTION_SENSORS,
    val gamepadTouchpadAsMouse: Boolean = DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE,
    val gamepadMotionSensorsFallbackToDevice: Boolean = DEFAULT_GAMEPAD_MOTION_FALLBACK,
    val mouseNavButtons: Boolean = DEFAULT_MOUSE_NAV_BUTTONS,
    val unlockFps: Boolean = DEFAULT_UNLOCK_FPS,
    val vibrateOsc: Boolean = DEFAULT_VIBRATE_OSC,
    val vibrateFallbackToDevice: Boolean = DEFAULT_VIBRATE_FALLBACK,
    val statsEnabled: Boolean = STATS_ENABLED,
    val databaseEnabled: Boolean = DEFAULT_DATABASE_ENABLED,
    val sunFavorites: String = SUN_FAVORITES_DEFAULT_PREF,
    val favorites: String = FAVORITES_DEFAULT_PREF,
    val renderer: String = DEFAULT_RENDERER_PREF,
    val fingerprint: String = DEFAULT_FINGERPRINT_PREF,
    val header: Boolean = DEFAULT_HEADER_PREF,
    val vibrateFallbackToDeviceStrength: Int = DEFAULT_VIBRATE_FALLBACK_STRENGTH,
    val touchscreenTrackpad: Boolean = DEFAULT_TOUCHSCREEN_TRACKPAD,
    val framePacing: Int = FRAME_PACING_MIN_LATENCY,
    val framePacingString: String = DEFAULT_FRAME_PACING,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val bitrate: Int = 10000,
    val videoSr: Boolean = DEFAULT_VIDEO_SR,
    val deadzonePercentage: Int = DEFAULT_DEADZONE,
    val oscOpacity: Int = DEFAULT_OPACITY,
    val mlDownloaded: Boolean = DEFAULT_ML_DOWNLOADED,
    val videoFormat: FormatOption = FormatOption.AUTO,
    val videoFormatConfig: String = DEFAULT_VIDEO_FORMAT,
    val audioConfiguration: StarBridge.AudioConfiguration = StarBridge.AUDIO_CONFIGURATION_STEREO,
    val audioConfig: String = DEFAULT_AUDIO_CONFIG,
    val analogStickForScrolling: AnalogStickForScrolling = AnalogStickForScrolling.RIGHT,
    val analogScrolling: String = DEFAULT_ANALOG_STICK_FOR_SCROLLING,
    val selectedPosition: Int = 0,
    val videoPosition: Int = 0,
    val favSelectedPosition: Int = 0,
    val appViewSelectedPosition: Int = 0,
    val crashCount: Int = 0,
    val lastNotifiedCrashCount: Int = 0,
    val glesValue: Int = 0,
    val aaudio: Boolean = DEFAULT_AAUDIO,
    val areCaptionsVisible: Boolean = DEFAULT_CAPTIONS,
    val audioOutputMode: AudioOutputMode = AudioOutputMode.PCM
)

object PreferenceKeys {

    private var _cachedConfigFlow: StateFlow<DataStoreConfiguration>? = null

    // Standard resolutions
    val STANDARD_RESOLUTIONS = listOf(
        Size(1280, 720),
        Size(1920, 1080),
        Size(2560, 1440),
        Size(3840, 2160)
    )

    private fun Preferences.toConfig(): DataStoreConfiguration {
        val resStr = this[stringPreferencesKey(RESOLUTION_PREF_STRING)] ?: getDeviceResolution()
        val fpsStr = this[stringPreferencesKey(FPS_PREF_STRING)] ?: DEFAULT_FPS

        val width = getWidthFromResString(resStr) ?: 1920
        val height = getHeightFromResString(resStr) ?: 1080
        val fps = fpsStr.toIntOrNull() ?: 60

        return DataStoreConfiguration(
            resolution = resStr,
            stretchVideo = this[booleanPreferencesKey(STRETCH_PREF_STRING)] ?: DEFAULT_STRETCH,
            width = width,
            height = height,
            fps = fps,
            bitrate = this[intPreferencesKey(BITRATE_PREF_STRING)] ?: calculateBitrate(
                resStr,
                fpsStr
            ),
            videoSr = this[booleanPreferencesKey(VIDEO_SR_STRING)] ?: DEFAULT_VIDEO_SR,
            leanback = this[booleanPreferencesKey(LEANBACK_PREF_STRING)] ?: LEAN_BACK_DEFAULT,
            enableHdr = (this[booleanPreferencesKey(ENABLE_HDR_PREF_STRING)] ?: DEFAULT_ENABLE_HDR)
                    && !isShieldAtvFirmwareWithBrokenHdr,
            smallIconMode = this[booleanPreferencesKey(SMALL_ICONS_PREF_STRING)]
                ?: SMALL_ICONS_DEFAULT,
            multiController = this[booleanPreferencesKey(MULTI_CONTROLLER_PREF_STRING)]
                ?: DEFAULT_MULTI_CONTROLLER,
            usbDriver = this[booleanPreferencesKey(USB_DRIVER_PREF_SRING)] ?: DEFAULT_USB_DRIVER,
            flipFaceButtons = this[booleanPreferencesKey(FLIP_FACE_BUTTONS_PREF_STRING)]
                ?: DEFAULT_FLIP_FACE_BUTTONS,
            onlyL3R3 = this[booleanPreferencesKey(ONLY_L3_R3_PREF_STRING)] ?: ONLY_L3_R3_DEFAULT,
            enablePip = this[booleanPreferencesKey(ENABLE_PIP_PREF_STRING)] ?: DEFAULT_ENABLE_PIP,
            enablePerfOverlay = this[booleanPreferencesKey(ENABLE_PERF_OVERLAY_STRING)]
                ?: DEFAULT_ENABLE_PERF_OVERLAY,
            mouseEmulation = this[booleanPreferencesKey(MOUSE_EMULATION_STRING)]
                ?: DEFAULT_MOUSE_EMULATION,
            mouseNavButtons = this[booleanPreferencesKey(MOUSE_NAV_BUTTONS_STRING)]
                ?: DEFAULT_MOUSE_NAV_BUTTONS,
            vibrateOsc = this[booleanPreferencesKey(VIBRATE_OSC_PREF_STRING)]
                ?: DEFAULT_VIBRATE_OSC,
            vibrateFallbackToDevice = this[booleanPreferencesKey(VIBRATE_FALLBACK_PREF_STRING)]
                ?: DEFAULT_VIBRATE_FALLBACK,
            statsEnabled = this[booleanPreferencesKey(STATS_PREF_STRING)] ?: STATS_ENABLED,
            framePacingString = this[stringPreferencesKey(FRAME_PACING_PREF_STRING)]
                ?: DEFAULT_FRAME_PACING,
            framePacing = getFramePacingValue(this),
            videoFormat = getVideoFormatValue(this),
            videoFormatConfig = this[stringPreferencesKey(VIDEO_FORMAT_PREF_STRING)]
                ?: DEFAULT_VIDEO_FORMAT,
            analogStickForScrolling = getAnalogStickForScrollingValue(this),
            analogScrolling = this[stringPreferencesKey(ANALOG_SCROLLING_PREF_STRING)]
                ?: DEFAULT_ANALOG_STICK_FOR_SCROLLING,
            audioConfiguration = getAudioConfiguration(this),
            audioConfig = this[stringPreferencesKey(AUDIO_CONFIG_PREF_STRING)]
                ?: DEFAULT_AUDIO_CONFIG,
            language = this[stringPreferencesKey(LANGUAGE_PREF_STRING)] ?: DEFAULT_LANGUAGE_STRING,
            disableWarnings = this[booleanPreferencesKey(DISABLE_TOASTS_PREF_STRING)]
                ?: DEFAULT_DISABLE_TOASTS,
            playHostAudio = this[booleanPreferencesKey(HOST_AUDIO_PREF_STRING)]
                ?: DEFAULT_HOST_AUDIO,
            fullRange = this[booleanPreferencesKey(FULL_RANGE_PREF_STRING)] ?: DEFAULT_FULL_RANGE,
            gamepadMotionSensors = this[booleanPreferencesKey(GAMEPAD_MOTION_SENSORS_PREF_STRING)]
                ?: DEFAULT_GAMEPAD_MOTION_SENSORS,
            gamepadTouchpadAsMouse = this[booleanPreferencesKey(
                GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING
            )] ?: DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE,
            gamepadMotionSensorsFallbackToDevice = this[booleanPreferencesKey(
                GAMEPAD_MOTION_FALLBACK_PREF_STRING
            )] ?: DEFAULT_GAMEPAD_MOTION_FALLBACK,
            touchscreenTrackpad = this[booleanPreferencesKey(TOUCHSCREEN_TRACKPAD_PREF_STRING)]
                ?: DEFAULT_TOUCHSCREEN_TRACKPAD,
            absoluteMouseMode = this[booleanPreferencesKey(ABSOLUTE_MOUSE_MODE_PREF_STRING)]
                ?: DEFAULT_ABSOLUTE_MOUSE_MODE,
            enableAudioFx = this[booleanPreferencesKey(ENABLE_AUDIO_FX_PREF_STRING)]
                ?: DEFAULT_ENABLE_AUDIO_FX,
            reduceRefreshRate = this[booleanPreferencesKey(REDUCE_REFRESH_RATE_PREF_STRING)]
                ?: DEFAULT_REDUCE_REFRESH_RATE,
            unlockFps = this[booleanPreferencesKey(UNLOCK_FPS_STRING)] ?: DEFAULT_UNLOCK_FPS,
            deadzonePercentage = this[intPreferencesKey(DEADZONE_PREF_STRING)] ?: DEFAULT_DEADZONE,
            oscOpacity = this[intPreferencesKey(OSC_OPACITY_PREF_STRING)] ?: DEFAULT_OPACITY,
            bindAllUsb = this[booleanPreferencesKey(BIND_ALL_USB_STRING)] ?: DEFAULT_BIND_ALL_USB,
            mlDownloaded = this[booleanPreferencesKey(ML_DOWNLOADED)] ?: DEFAULT_ML_DOWNLOADED,
            enableLatencyToast = this[booleanPreferencesKey(LATENCY_TOAST_PREF_STRING)]
                ?: DEFAULT_LATENCY_TOAST,
            onscreenController = this[booleanPreferencesKey(ONSCREEN_CONTROLLER_PREF_STRING)]
                ?: ONSCREEN_CONTROLLER_DEFAULT,
            vibrateFallbackToDeviceStrength = this[intPreferencesKey(
                VIBRATE_FALLBACK_STRENGTH_PREF_STRING
            )] ?: DEFAULT_VIBRATE_FALLBACK_STRENGTH,
            crashCount = this[intPreferencesKey(PREF_KEY_CRASH_COUNT)] ?: 0,
            lastNotifiedCrashCount = this[intPreferencesKey(PREF_KEY_LAST_NOTIFIED_CRASH_COUNT)]
                ?: 0,
            databaseEnabled = this[booleanPreferencesKey(DATABASE_PREF)]
                ?: DEFAULT_DATABASE_ENABLED,
            sunFavorites = this[stringPreferencesKey(SUN_FAVORITES_PREF)]
                ?: SUN_FAVORITES_DEFAULT_PREF,
            favorites = this[stringPreferencesKey(FAVORITES_PREF)] ?: FAVORITES_DEFAULT_PREF,
            renderer = this[stringPreferencesKey(RENDERER_PREF)] ?: DEFAULT_RENDERER_PREF,
            fingerprint = this[stringPreferencesKey(FINGERPRINT_PREF)] ?: DEFAULT_FINGERPRINT_PREF,
            header = this[booleanPreferencesKey(HEADER_PREF)] ?: DEFAULT_HEADER_PREF,
            selectedPosition = this[intPreferencesKey(KEY_SELECTED_POSITION)] ?: 0,
            videoPosition = this[intPreferencesKey(VIDEO_SELECTED_POSITION)] ?: 0,
            favSelectedPosition = this[intPreferencesKey(KEY_FAV_SELECTED_POSITION)] ?: 0,
            appViewSelectedPosition = this[intPreferencesKey(APP_VIEW_KEY_SELECTED_POSITION)] ?: 0,
            glesValue = this[intPreferencesKey(GLES_PREF_STRING)] ?: 0,
            aaudio = this[booleanPreferencesKey(AAUDIO_PREF_STRING)] ?: DEFAULT_AAUDIO,
            areCaptionsVisible = this[booleanPreferencesKey(DEFAULT_CAPTIONS_STRING)]
                ?: DEFAULT_CAPTIONS,
            audioOutputMode = AudioOutputMode.fromPreference(this[stringPreferencesKey(AUDIO_OUTPUT_MODE_PREF_STRING)] ?: DEFAULT_AUDIO_MODE)
        )
    }

    fun fromPreferences(context: Context): DataStoreConfiguration {
        return context.dataStoreConfigFlow.value
    }

    val Context.dataStoreConfigFlow: StateFlow<DataStoreConfiguration>
        get() {
            return _cachedConfigFlow ?: this.dataStore.data.map { prefs ->
                prefs.toConfig()
            }.stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = DataStoreConfiguration(leanback = false)
            ).also { _cachedConfigFlow = it }
        }

    // Get the device resolution, default to 1080p on 4K devices
    fun getDeviceResolution(): String {
        var targetResolution = ""

        val displayManager = DisplayManagerCompat.getInstance(instance)
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

        display?.let {
            for (candidate in DisplayCompat.getSupportedModes(
                instance,
                it
            )) {
                val width = candidate.physicalWidth.coerceAtLeast(candidate.physicalHeight)
                val height = candidate.physicalWidth.coerceAtMost(candidate.physicalHeight)

                targetResolution = if (width >= 3840 || height >= 2160) {
                    DEFAULT_RESOLUTION
                } else {
                    STANDARD_RESOLUTION
                }
            }
        }
        return targetResolution
    }

    // Get the audio configuration
    fun getAudioConfiguration(prefs: Preferences): StarBridge.AudioConfiguration {
        val audioConfig =
            prefs[stringPreferencesKey(AUDIO_CONFIG_PREF_STRING)] ?: DEFAULT_AUDIO_CONFIG
        return when (audioConfig) {
            "71" -> StarBridge.AUDIO_CONFIGURATION_71_SURROUND
            "51" -> StarBridge.AUDIO_CONFIGURATION_51_SURROUND
            else -> StarBridge.AUDIO_CONFIGURATION_STEREO
        }
    }

    // Returns true if the resolution is not a standard resolution
    fun isNativeResolution(width: Int, height: Int): Boolean {
        return Size(width, height) !in STANDARD_RESOLUTIONS
    }

    // If we have a screen that has semi-square dimensions, we may want to change our behavior
    // to allow any orientation and vertical+horizontal resolutions.
    fun isSquarishScreen(width: Int, height: Int): Boolean {
        val longDim = max(width.toFloat(), height.toFloat())
        val shortDim = min(width.toFloat(), height.toFloat())

        return longDim / shortDim < SQUARISH_SCREEN_ASPECT_RATIO_THRESHOLD
    }

    // Returns true if the screen is squarish
    fun isSquarishScreen(display: Display, context: Context): Boolean {
        val displayMode = DisplayCompat.getMode(context, display)
        return isSquarishScreen(displayMode.physicalWidth, displayMode.physicalHeight)
    }

    // Calculate the default bitrate from bridge
    fun calculateBitrate(resString: String, fpsString: String): Int {
        val width = getWidthFromResString(resString) ?: 1280
        val height = getHeightFromResString(resString) ?: 720
        val fps = fpsString.toIntOrNull() ?: 60

        val frameRateFactor = if (fps <= 60) {
            fps / 30.0
        } else {
            sqrt(fps / 60.0)
        }

        val pixelVals = intArrayOf(
            1280 * 720,
            1920 * 1080,
            2560 * 1440,
            3840 * 2160,
            -1,
        )
        val factorVals = intArrayOf(
            10,
            20,
            30,
            50,
            -1
        )

        // Calculate the resolution factor by linear interpolation of the resolution table
        var resolutionFactor: Float
        val pixels = width * height
        run {
            var i = 0
            while (true) {
                if (pixels == pixelVals[i]) {
                    resolutionFactor = factorVals[i].toFloat()
                    break
                } else if (pixels < pixelVals[i]) {
                    resolutionFactor = if (i == 0) {
                        factorVals[i].toFloat()
                    } else {
                        ((pixels - pixelVals[i - 1]).toFloat() / (pixelVals[i] - pixelVals[i - 1])) * (factorVals[i] - factorVals[i - 1]) + factorVals[i - 1]
                    }
                    break
                } else if (pixelVals[i] == -1) {
                    // Never go above the highest resolution entry
                    resolutionFactor = factorVals[i - 1].toFloat()
                    break
                }
                i++
            }
        }

        return (resolutionFactor * frameRateFactor).roundToInt() * 1000
    }

    // Get the width from a resolution string
    private fun getWidthFromResString(resString: String): Int? {
        val parts = resString.split("x").filter { it.isNotEmpty() }
        return if (parts.size == 2) parts[0].toIntOrNull() else null
    }

    // Get the height from a resolution string
    private fun getHeightFromResString(resString: String): Int? {
        val parts = resString.split("x").filter { it.isNotEmpty() }
        return if (parts.size == 2) parts[1].toIntOrNull() else null
    }

    // Get the video format
    private fun getVideoFormatValue(prefs: Preferences): FormatOption {
        val videoFormat =
            prefs[stringPreferencesKey(VIDEO_FORMAT_PREF_STRING)] ?: DEFAULT_VIDEO_FORMAT
        return when (videoFormat) {
            "forceav1" -> FormatOption.FORCE_AV1
            "forceh265" -> FormatOption.FORCE_HEVC
            "neverh265" -> FormatOption.FORCE_H264
            // Default to AUTO if no match
            else -> FormatOption.AUTO
        }
    }

    // Get the frame pacing
    private fun getFramePacingValue(prefs: Preferences): Int {
        val framePacing =
            prefs[stringPreferencesKey(FRAME_PACING_PREF_STRING)] ?: DEFAULT_FRAME_PACING
        return when (framePacing) {
            "latency" -> FRAME_PACING_MIN_LATENCY
            "balanced" -> FRAME_PACING_BALANCED
            "cap-fps" -> FRAME_PACING_CAP_FPS
            "smoothness" -> FRAME_PACING_MAX_SMOOTHNESS
            else -> FRAME_PACING_MIN_LATENCY
        }
    }

    // Shield TV firmware with broken HDR
    val isShieldAtvFirmwareWithBrokenHdr: Boolean
        get() = Build.MANUFACTURER.equals(NVIDIA_MANUFACTURER, ignoreCase = true) &&
                Build.FINGERPRINT.contains(BROKEN_HDR_FIRMWARE_FINGERPRINT)

    // Get the analog stick for scrolling
    private fun getAnalogStickForScrollingValue(prefs: Preferences): AnalogStickForScrolling {
        val analogScrolling = prefs[stringPreferencesKey(ANALOG_SCROLLING_PREF_STRING)]
            ?: DEFAULT_ANALOG_STICK_FOR_SCROLLING
        return when (analogScrolling) {
            "right" -> AnalogStickForScrolling.RIGHT
            "left" -> AnalogStickForScrolling.LEFT
            else -> AnalogStickForScrolling.NONE
        }
    }

    // Safe Gson
    private val safeGson = GsonBuilder()
        .excludeFieldsWithModifiers(
            java.lang.reflect.Modifier.STATIC,
            java.lang.reflect.Modifier.TRANSIENT
        )
        .create()

    // Put a parcelable object to the datastore
    suspend fun putParcelableObject(key: Preferences.Key<String>, nvApp: NvApp?, context: Context) {
        if (nvApp == null) return

        val json = safeGson.toJson(nvApp)
        context.dataStore.edit { preferences ->
            preferences[key] = json
        }
    }

    // Get a parcelable object from the datastore
    suspend fun getParcelable(
        key: Preferences.Key<String>,
        default: NvApp?,
        context: Context
    ): NvApp? {
        val preferences = context.dataStore.data.first()
        val json = preferences[key] ?: ""

        if (json.isEmpty()) return default

        return try {
            safeGson.fromJson(json, NvApp::class.java)
        } catch (_: JsonSyntaxException) {
            warning("Starlight: Failed to parse object ${key.name}")
            default
        }
    }

    // Put an array list to the datastore
    suspend fun saveArrayList(
        key: Preferences.Key<String>,
        list: MutableList<NvApp>,
        context: Context
    ) {
        val gson = Gson()
        val json = gson.toJson(list)

        context.dataStore.edit { preferences ->
            preferences[key] = json
        }
    }

    // Get an array list from the datastore
    suspend fun getArrayList(key: Preferences.Key<String>, context: Context): MutableList<NvApp>? {
        val json = context.dataStore.data.map { preferences ->
            preferences[key] ?: ""
        }.first()

        return try {
            val gson = Gson()
            val type = object : TypeToken<MutableList<NvApp?>?>() {}.type
            gson.fromJson(json, type)
        } catch (_: JsonSyntaxException) {
            warning("Starlight: Failed to parse list ${key.name}")
            mutableListOf()
        }
    }

    // Delete an array list from the datastore
    suspend fun deleteArrayList(key: Preferences.Key<String>, context: Context) {
        context.dataStore.edit { preferences ->
            preferences.remove(key)
        }
    }

    // Get the dynamic hidden apps key
    private fun getDynamicHiddenAppsKey(uuid: String?): Preferences.Key<Set<String>> {
        return stringSetPreferencesKey(uuid!!)
    }

    // Put a set of hidden apps to the datastore
    suspend fun putHiddenAppsSetSync(uuid: String?, value: Set<String>, context: Context) {
        val dynamicKey = getDynamicHiddenAppsKey(uuid)
        context.dataStore.edit { preferences ->
            preferences[dynamicKey] = value
        }
    }

    // Get a set of hidden apps from the datastore
    suspend fun getHiddenAppsSetSync(
        uuid: String?,
        defaultValue: Set<String> = emptySet(), context: Context
    ): Set<String> {
        val dynamicKey = getDynamicHiddenAppsKey(uuid)
        return context.dataStore.data.map { preferences ->
            preferences[dynamicKey] ?: defaultValue
        }.first()
    }

    val Context.listLanguageFlow: Flow<String>
        get() = dataStoreConfigFlow.map { it.language }.distinctUntilChanged()

    val Context.unlockFpsFlow: Flow<Boolean>
        get() = dataStoreConfigFlow.map { it.unlockFps }.distinctUntilChanged()

    val Context.resolutionFlow: Flow<String>
        get() = dataStoreConfigFlow.map { it.resolution }.distinctUntilChanged()

    val Context.rendererFlow: Flow<String>
        get() = dataStoreConfigFlow.map { it.renderer }.distinctUntilChanged()

}
