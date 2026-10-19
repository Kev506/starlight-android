package com.starlight.utils

import android.os.LocaleList
import android.view.KeyEvent
import java.util.Locale

/**
 * Created by Kevin Andrews on 26/11/2025.
 */
object Constants {

    const val SETUP_GUIDE_URL =
        "https://tiki.starlight-stream.com/tiki-index.php?page=Setup+Guide"
    const val TROUBLESHOOTING_URL =
        "https://tiki.starlight-stream.com/tiki-index.php?page=Troubleshooting"
    const val GAME_STREAM_EOL_FAQ_URL =
        "https://tiki.starlight-stream.com/tiki-read_article.php?articleId=2"
    const val IGDB_URL = "https://www.igdb.com/"
    const val IGDB_API_BASEURL = "https://api.igdb.com/v4/"
    const val TWITCH_AUTH_BASEURL = "https://id.twitch.tv/"
    const val BASE_COVER_URL = "https://images.igdb.com/igdb/image/upload/t_cover_big/"
    const val BASE_IMAGE_URL = "https://images.igdb.com/igdb/image/upload/t_1080p/"
    const val HQ_THUMBNAIL_URL = "https://img.youtube.com/vi/"
    const val PRIVACY_URL = "https://starlight-stream.com/Privacy-tv/"
    const val CONNECTION_TEST_SERVER = "android.con_test.starlight-stream.com"

    const val APPLICATION_ID = "com.starlight"
    const val STARLIGHT_PREF_NAME = "starlight_settings"
    const val APPNAME: String = ""
    const val PCNAME: String = ""
    const val UUID: String = ""
    const val UNIQUEID: String = ""
    const val APPID: String = ""
    const val NAME: String = ""
    const val SHOW_HIDDEN_APPS = false
    const val PAIR_STATE = false
    const val IS_HDR_SUPPORTED = false
    const val COMPUTER_CHANGED = false
    const val IS_FAVS = false
    const val CAN_GO_BACK = false
    const val DATABASE_CLEARED = false
    const val URL: String = ""

    var LAST_KEY_DOWN_TIME = 100L
    const val VIDEO_SELECTED_POSITION = "video_position"
    const val KEY_SELECTED_POSITION = "selected_position"
    const val KEY_FAV_SELECTED_POSITION = "favs_selected_position"
    const val APP_VIEW_KEY_SELECTED_POSITION = "appview_selected_position"

    const val START_OR_RESUME_ID = 1
    const val QUIT_ID = 2
    const val START_WITH_QUIT = 4
    const val HIDE_APP_ID = 7
    const val ADD_FAVORITE_ID = 8
    const val FULL_APP_LIST_ID = 9
    const val VIEW_DETAILS_ID = 5

    const val PAIR_ID = 2
    const val WOL_ID = 4
    const val DELETE_ID = 5
    const val VIEW_COMPUTER_DETAILS_ID = 8
    const val TEST_NETWORK_ID = 10
    const val GAMESTREAM_EOL_ID = 11

    const val ACTION_DOWN = KeyEvent.ACTION_DOWN
    const val ACTION_UP = KeyEvent.ACTION_UP

    @Suppress("DEPRECATION")
    const val ACTION_MULTIPLE = KeyEvent.ACTION_MULTIPLE

    const val REFERENCE_HORIZ_RES = 1280
    const val REFERENCE_VERT_RES = 720
    const val STYLUS_DOWN_DEAD_ZONE_DELAY = 100
    const val STYLUS_DOWN_DEAD_ZONE_RADIUS = 20
    const val STYLUS_UP_DEAD_ZONE_DELAY = 150
    const val STYLUS_UP_DEAD_ZONE_RADIUS = 50
    const val THREE_FINGER_TAP_THRESHOLD = 300
    const val SAMSUNG_DEX_MOUSE_SOURCE = 12290

    const val SERVER_PREF = "server"
    const val USERNAME_PREF = "username"
    const val PASSWORD_PREF = "password"
    const val DIRECTORY_PREF = "directory"
    const val CLIENT_ID = "clientid"
    const val CLIENT_SECRET = "clientsecret"
    const val DEFAULT_SERVER_PREF = ""
    const val DEFAULT_USERNAME_PREF = ""
    const val DEFAULT_PASSWORD_PREF = ""
    const val DEFAULT_DIRECTORY_PREF = ""
    const val HEADER_PREF = "headerWritten"
    const val DEFAULT_HEADER_PREF = false

    const val ACCESS_TOKEN_KEY = "access_token"
    const val EXPIRATION_TIME_KEY = "expiration_timestamp"
    const val GLES_PREF_STRING = "system_gles_version"
    const val VIDEO_SR_STRING = "checkbox_video_sr"
    const val DEFAULT_VIDEO_SR = false
    const val AAUDIO_PREF_STRING = "checkbox_aaudio"
    const val DEFAULT_AAUDIO = false
    const val DEFAULT_CAPTIONS_STRING = "checkbox_captions"
    const val DEFAULT_CAPTIONS = false
    const val PREF_KEY_LAST_NOTIFIED_CRASH_COUNT = "LastNotifiedCrashCount"
    const val DEFAULT_DATABASE_ENABLED = false
    const val RENDERER_PREF = "Renderer"
    const val DEFAULT_RENDERER_PREF = ""
    const val FINGERPRINT_PREF = "Fingerprint"
    const val DEFAULT_FINGERPRINT_PREF = ""
    const val DATABASE_PREF = "database"
    const val FAVORITES_DEFAULT_PREF = ""
    const val SUN_FAVORITES_DEFAULT_PREF = ""
    const val FAVORITES_PREF = "favorites"
    const val SUN_FAVORITES_PREF = "sun_favorites"
    const val STRETCH_PREF_STRING = "checkbox_stretch_video"
    const val DISABLE_TOASTS_PREF_STRING = "checkbox_disable_warnings"
    const val HOST_AUDIO_PREF_STRING = "checkbox_host_audio"
    const val DEADZONE_PREF_STRING = "seekbar_deadzone"
    const val OSC_OPACITY_PREF_STRING = "seekbar_osc_opacity"
    const val MULTI_CONTROLLER_PREF_STRING = "checkbox_multi_controller"
    const val USB_DRIVER_PREF_SRING = "checkbox_usb_driver"
    const val ONSCREEN_CONTROLLER_PREF_STRING = "checkbox_show_onscreen_controls"
    const val ONLY_L3_R3_PREF_STRING = "checkbox_only_show_L3R3"
    const val ENABLE_HDR_PREF_STRING = "hdr_switch"
    const val DEFAULT_ENABLE_HDR = false
    const val ENABLE_PIP_PREF_STRING = "checkbox_enable_pip"
    const val ENABLE_PERF_OVERLAY_STRING = "checkbox_enable_perf_overlay"
    const val BIND_ALL_USB_STRING = "checkbox_usb_bind_all"
    const val MOUSE_EMULATION_STRING = "checkbox_mouse_emulation"
    const val MOUSE_NAV_BUTTONS_STRING = "checkbox_mouse_nav_buttons"
    const val VIBRATE_OSC_PREF_STRING = "checkbox_vibrate_osc"
    const val VIBRATE_FALLBACK_PREF_STRING = "checkbox_vibrate_fallback"
    const val FLIP_FACE_BUTTONS_PREF_STRING = "checkbox_flip_face_buttons"
    const val TOUCHSCREEN_TRACKPAD_PREF_STRING = "checkbox_touchscreen_trackpad"
    const val LATENCY_TOAST_PREF_STRING = "checkbox_enable_post_stream_toast"
    const val ABSOLUTE_MOUSE_MODE_PREF_STRING = "checkbox_absolute_mouse_mode"
    const val ENABLE_AUDIO_FX_PREF_STRING = "checkbox_enable_audiofx"
    const val REDUCE_REFRESH_RATE_PREF_STRING = "checkbox_reduce_refresh_rate"
    const val FULL_RANGE_PREF_STRING = "checkbox_full_range"
    const val GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING =
        "checkbox_gamepad_touchpad_as_mouse"
    const val GAMEPAD_MOTION_SENSORS_PREF_STRING = "checkbox_gamepad_motion_sensors"
    const val GAMEPAD_MOTION_FALLBACK_PREF_STRING = "checkbox_gamepad_motion_fallback"
    const val DEFAULT_VIDEO_FORMAT = "auto"
    const val DEFAULT_ANALOG_STICK_FOR_SCROLLING = "right"
    const val DEFAULT_FRAME_PACING = "latency"
    const val VIBRATE_FALLBACK_STRENGTH_PREF_STRING =
        "seekbar_vibrate_fallback_strength"
    const val VIDEO_FORMAT_PREF_STRING = "video_format"
    const val ANALOG_SCROLLING_PREF_STRING = "analog_scrolling"
    const val FRAME_PACING_PREF_STRING = "frame_pacing"
    const val AUDIO_CONFIG_PREF_STRING = "list_audio_config"
    const val SMALL_ICONS_PREF_STRING = "checkbox_small_icon_mode"
    const val RESOLUTION_PREF_STRING = "list_resolution"
    const val FPS_PREF_STRING = "list_fps"
    const val BITRATE_PREF_STRING = "seekbar_bitrate_kbps"
    const val LEANBACK_PREF_STRING = "checkbox_leanback"
    const val LANGUAGE_PREF_STRING = "list_languages"
    const val UNLOCK_FPS_STRING = "checkbox_unlock_fps"
    const val STATS_PREF_STRING = "checkbox_enable_stats"
    const val AUDIO_OUTPUT_MODE_PREF_STRING = "list_audio_output_mode"
    const val DEFAULT_AUDIO_MODE = "pcm"
    const val DEFAULT_RESOLUTION = "1920x1080"
    const val STANDARD_RESOLUTION = "1280x720"
    const val DEFAULT_FPS = "60"
    const val ML_DOWNLOADED = "mldownloaded"

    val DEFAULT_LANGUAGE = LocaleList.getDefault().toLanguageTags()
    val DEFAULT_LANGUAGE_STRING: String
        get() = Locale.getDefault().language

    const val DEFAULT_ML_DOWNLOADED = false
    const val SMALL_ICONS_DEFAULT = false
    const val LEAN_BACK_DEFAULT = false
    const val DEFAULT_STRETCH = false
    const val DEFAULT_DISABLE_TOASTS = false
    const val DEFAULT_HOST_AUDIO = false
    const val DEFAULT_MULTI_CONTROLLER = true
    const val DEFAULT_USB_DRIVER = true
    const val ONSCREEN_CONTROLLER_DEFAULT = false
    const val ONLY_L3_R3_DEFAULT = false
    const val DEFAULT_ENABLE_PIP = false
    const val DEFAULT_ENABLE_PERF_OVERLAY = false
    const val DEFAULT_BIND_ALL_USB = false
    const val DEFAULT_MOUSE_EMULATION = false
    const val DEFAULT_MOUSE_NAV_BUTTONS = false
    const val DEFAULT_UNLOCK_FPS = false
    const val STATS_ENABLED = false
    const val DEFAULT_VIBRATE_OSC = true
    const val DEFAULT_VIBRATE_FALLBACK = false
    const val DEFAULT_FLIP_FACE_BUTTONS = false
    const val DEFAULT_TOUCHSCREEN_TRACKPAD = true
    const val DEFAULT_LATENCY_TOAST = false
    const val DEFAULT_ABSOLUTE_MOUSE_MODE = false
    const val DEFAULT_ENABLE_AUDIO_FX = false
    const val DEFAULT_REDUCE_REFRESH_RATE = false
    const val DEFAULT_FULL_RANGE = false
    const val DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE = false
    const val DEFAULT_GAMEPAD_MOTION_SENSORS = true
    const val DEFAULT_GAMEPAD_MOTION_FALLBACK = false

    const val DEFAULT_AUDIO_CONFIG = "2" // Stereo
    const val DEFAULT_VIBRATE_FALLBACK_STRENGTH = 100
    const val DEFAULT_DEADZONE = 7
    const val DEFAULT_OPACITY = 90

    const val SQUARISH_SCREEN_ASPECT_RATIO_THRESHOLD = 1.3f
    const val FRAME_PACING_MIN_LATENCY = 0
    const val FRAME_PACING_BALANCED = 1
    const val FRAME_PACING_CAP_FPS = 2
    const val FRAME_PACING_MAX_SMOOTHNESS = 3

    const val NVIDIA_MANUFACTURER = "NVIDIA"
    const val BROKEN_HDR_FIRMWARE_FINGERPRINT = "PPR1.180610.011/4079208_2235.1395"

    const val PREF_KEY_CRASH_COUNT = "CrashCount"
}