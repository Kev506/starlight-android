package com.starlight.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.Display
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.hardware.display.DisplayManagerCompat
import androidx.core.view.DisplayCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.starlight.R
import com.starlight.binding.video.MediaCodecHelper
import com.starlight.models.MainViewModel
import com.starlight.navigation.Screens
import com.starlight.preferences.PreferenceKeys
import com.starlight.preferences.PreferenceKeys.dataStoreConfigFlow
import com.starlight.preferences.PreferenceKeys.listLanguageFlow
import com.starlight.preferences.PreferenceKeys.rendererFlow
import com.starlight.preferences.PreferenceKeys.resolutionFlow
import com.starlight.preferences.PreferenceKeys.unlockFpsFlow
import com.starlight.ui.dialogs.AlertDialog
import com.starlight.ui.dialogs.ButtonListAlertDialog
import com.starlight.ui.dialogs.DeadZoneDialog
import com.starlight.ui.dialogs.DownloadDialog
import com.starlight.ui.dialogs.SeekBarDialog
import com.starlight.utils.Constants.AAUDIO_PREF_STRING
import com.starlight.utils.Constants.ABSOLUTE_MOUSE_MODE_PREF_STRING
import com.starlight.utils.Constants.AUDIO_CONFIG_PREF_STRING
import com.starlight.utils.Constants.AUDIO_OUTPUT_MODE_PREF_STRING
import com.starlight.utils.Constants.BIND_ALL_USB_STRING
import com.starlight.utils.Constants.BITRATE_PREF_STRING
import com.starlight.utils.Constants.DEADZONE_PREF_STRING
import com.starlight.utils.Constants.DEFAULT_ANALOG_STICK_FOR_SCROLLING
import com.starlight.utils.Constants.DEFAULT_CAPTIONS_STRING
import com.starlight.utils.Constants.DEFAULT_LANGUAGE
import com.starlight.utils.Constants.DEFAULT_LANGUAGE_STRING
import com.starlight.utils.Constants.DEFAULT_RENDERER_PREF
import com.starlight.utils.Constants.DEFAULT_RESOLUTION
import com.starlight.utils.Constants.DISABLE_TOASTS_PREF_STRING
import com.starlight.utils.Constants.ENABLE_AUDIO_FX_PREF_STRING
import com.starlight.utils.Constants.ENABLE_PERF_OVERLAY_STRING
import com.starlight.utils.Constants.ENABLE_PIP_PREF_STRING
import com.starlight.utils.Constants.FPS_PREF_STRING
import com.starlight.utils.Constants.FRAME_PACING_PREF_STRING
import com.starlight.utils.Constants.FULL_RANGE_PREF_STRING
import com.starlight.utils.Constants.GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING
import com.starlight.utils.Constants.LANGUAGE_PREF_STRING
import com.starlight.utils.Constants.LATENCY_TOAST_PREF_STRING
import com.starlight.utils.Constants.LEANBACK_PREF_STRING
import com.starlight.utils.Constants.ML_DOWNLOADED
import com.starlight.utils.Constants.MOUSE_EMULATION_STRING
import com.starlight.utils.Constants.MOUSE_NAV_BUTTONS_STRING
import com.starlight.utils.Constants.MULTI_CONTROLLER_PREF_STRING
import com.starlight.utils.Constants.PRIVACY_URL
import com.starlight.utils.Constants.REDUCE_REFRESH_RATE_PREF_STRING
import com.starlight.utils.Constants.RESOLUTION_PREF_STRING
import com.starlight.utils.Constants.SMALL_ICONS_PREF_STRING
import com.starlight.utils.Constants.STATS_PREF_STRING
import com.starlight.utils.Constants.STRETCH_PREF_STRING
import com.starlight.utils.Constants.TROUBLESHOOTING_URL
import com.starlight.utils.Constants.UNLOCK_FPS_STRING
import com.starlight.utils.Constants.USB_DRIVER_PREF_SRING
import com.starlight.utils.Constants.VIDEO_FORMAT_PREF_STRING
import com.starlight.utils.Constants.VIDEO_SR_STRING
import com.starlight.utils.LimeLog
import com.starlight.utils.Starlight.Companion.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/**
 * Created by Kevin Andrews on 26/02/2026.
 */

@Composable
fun SettingsScreen(viewModel: MainViewModel) {

    val mainContentRequester = remember { FocusRequester() }
    val openAlertDialog = remember { mutableStateOf(false) }
    val buttonListAlertDialog = remember { mutableStateOf(false) }
    val seekBarAlertDialog = remember { mutableStateOf(false) }
    val deadZoneDialog = remember { mutableStateOf(false) }
    var img by remember { mutableStateOf<Int?>(null) }
    val dialogText = remember { mutableStateOf("") }
    val dialogTitle = remember { mutableStateOf("") }
    val confirmButtonText = remember { mutableStateOf("") }
    val dismissButtonText = remember { mutableStateOf("") }
    val isCancelRequired = remember { mutableStateOf(false) }
    val isStats = remember { mutableStateOf(false) }
    val isHelp = remember { mutableStateOf(false) }
    val isResolution = remember { mutableStateOf(false) }
    val isFps = remember { mutableStateOf(false) }
    val isFramePacing = remember { mutableStateOf(false) }
    val isFpsSupported = remember { mutableStateOf(true) }
    val isAudioConfig = remember { mutableStateOf(false) }
    val isAudioOutput = remember { mutableStateOf(false) }
    val isLanguage = remember { mutableStateOf(false) }
    val isVideoFormat = remember { mutableStateOf(false) }
    val isAnalog = remember { mutableStateOf(false) }
    val isAaudio = remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = LocalActivity.current as? ComponentActivity
    var maxSupportedResW by remember { mutableIntStateOf(1920) }
    var maxSupportedFps by remember { mutableFloatStateOf(60f) }
    var isDetectionFinished by remember { mutableStateOf(false) }
    val unsupportedFps = remember { mutableStateOf("") }
    val selectedLanguage = remember { mutableStateOf("") }
    val showLoader by viewModel.showLoaderState.observeAsState(false)
    val isMainView by viewModel.isMainView.observeAsState(false)
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val config by context.dataStoreConfigFlow.collectAsStateWithLifecycle(initialValue = null)
    val unlockFpsValue by context.unlockFpsFlow.collectAsStateWithLifecycle(false)
    val languageValue by context.listLanguageFlow.collectAsStateWithLifecycle(
        DEFAULT_LANGUAGE_STRING
    )
    val resolutionValue by context.resolutionFlow.collectAsStateWithLifecycle(DEFAULT_RESOLUTION)
    val renderer by context.rendererFlow.collectAsStateWithLifecycle(DEFAULT_RENDERER_PREF)

    val isLanguageDefault = remember(languageValue) {

        val isDefaultString = languageValue == DEFAULT_LANGUAGE_STRING
        val currentLocale = DEFAULT_LANGUAGE
        val isEnglish = currentLocale.contains("en")

        isDefaultString && isEnglish
    }

    val isApiLevelSupported = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2
    }

    val isFireOS = remember {
        activity!!.packageManager.hasSystemFeature(
            "com.amazon.software.fireos"
        )
    }

    val hasUsbHost = remember {
        activity!!.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
    }

    val hasPip = remember {
        activity!!.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    val isShield = remember {
        activity!!.packageManager.hasSystemFeature("com.nvidia.feature.shield")
    }

    val resolutionNames = stringArrayResource(R.array.resolution_names)
    val resolutionValuesArray = stringArrayResource(R.array.resolution_values)

    val fpsNames = stringArrayResource(R.array.fps_names)
    val fpsValues = stringArrayResource(R.array.fps_values)

    val framePacingNames = stringArrayResource(R.array.video_frame_pacing_names)
    val framePacingValues = stringArrayResource(R.array.video_frame_pacing_values)

    val audioNames = stringArrayResource(R.array.audio_config_names)
    val audioValues = stringArrayResource(R.array.audio_config_values)

    val languageNames = stringArrayResource(R.array.language_names)
    val languageValues = stringArrayResource(R.array.language_values)

    val videoFormatNames = stringArrayResource(R.array.video_format_names)
    val videoFormatValues = stringArrayResource(R.array.video_format_values)

    val analogNames = stringArrayResource(R.array.analog_scrolling_names)
    val analogValues = stringArrayResource(R.array.analog_scrolling_values)

    val audioOutputNames = stringArrayResource(R.array.audio_output_mode_names)
    val audioOutputValues = stringArrayResource(R.array.audio_output_mode_values)

    LaunchedEffect(Unit) {

        val displayManager = DisplayManagerCompat.getInstance(activity!!.applicationContext)
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

        var detectedMaxFps =
            DisplayCompat.getMode(activity, display!!).toMode()?.refreshRate ?: 60f
        var detectedMaxResW = 1920

        for (candidate in DisplayCompat.getSupportedModes(
            activity.applicationContext,
            display
        )) {
            val width = candidate.physicalWidth.coerceAtLeast(candidate.physicalHeight)
            val height = candidate.physicalWidth.coerceAtMost(candidate.physicalHeight)

            if ((width >= 3840 || height >= 2160)) {
                detectedMaxResW = detectedMaxResW.coerceAtLeast(3840)
            } else if ((width >= 2560 || height >= 1440)) {
                detectedMaxResW = detectedMaxResW.coerceAtLeast(2560)
            } else if ((width >= 1920 || height >= 1080)) {
                detectedMaxResW = detectedMaxResW.coerceAtLeast(1920)
            }

            val refreshRate = candidate.toMode()?.refreshRate ?: 0f
            if (refreshRate > detectedMaxFps) {
                detectedMaxFps = refreshRate
            }
        }

        MediaCodecHelper.initialize(
            activity.applicationContext,
            renderer
        )
        val avcDecoder = MediaCodecHelper.findProbableSafeDecoder(
            "video/avc", -1
        )
        val hevcDecoder = MediaCodecHelper.findProbableSafeDecoder(
            "video/hevc", -1
        )

        fun updateResWFromDecoder(decoder: android.media.MediaCodecInfo?, mime: String) {
            decoder?.let {
                val caps = it.getCapabilitiesForType(mime).videoCapabilities
                if (caps?.supportedWidths?.contains(1280) == true) {
                    if (caps.supportedWidths.contains(3840)) {
                        detectedMaxResW = detectedMaxResW.coerceAtLeast(3840)
                    } else if (caps.supportedWidths.contains(1920)) {
                        detectedMaxResW = detectedMaxResW.coerceAtLeast(1920)
                    }
                }
            }
        }

        updateResWFromDecoder(avcDecoder, "video/avc")
        updateResWFromDecoder(hevcDecoder, "video/hevc")

        maxSupportedResW = detectedMaxResW
        maxSupportedFps = detectedMaxFps
        isDetectionFinished = true

        LimeLog.info(
            "Maximum resolution slot: $maxSupportedResW, Max FPS: $maxSupportedFps"
        )
    }

    val supportedResolutions = remember(maxSupportedResW) {
        resolutionNames.zip(resolutionValuesArray).filter { (_, value) ->
            val resWidth = value.split("x").firstOrNull()?.toIntOrNull() ?: 0
            resWidth <= maxSupportedResW
        }
    }

    val supportedFpsOptions = remember(maxSupportedFps, unlockFpsValue) {
        fpsNames.zip(fpsValues).filter { (_, value) ->
            if (unlockFpsValue) true
            else {
                val fps = value.toFloatOrNull() ?: 0f
                fps <= (maxSupportedFps + 2f)
            }
        }
    }

    LaunchedEffect(supportedResolutions, isDetectionFinished) {
        if (isDetectionFinished && supportedResolutions.isNotEmpty() && supportedResolutions.none { it.second == resolutionValue }) {
            coroutineScope.launch(Dispatchers.IO) {
                context.dataStore.edit { preferences ->
                    preferences[stringPreferencesKey(RESOLUTION_PREF_STRING)] =
                        supportedResolutions.first().second
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            viewModel.setOptionsScreenState(true)
        }
    }

    config?.let { currentConfig ->

        LaunchedEffect(Unit) {
            mainContentRequester.requestFocus()
        }

        Surface(
            modifier = Modifier
                .padding(start = 45.dp, end = 45.dp, top = 30.dp)
                .focusRequester(mainContentRequester)
                .onFocusChanged {
                    if (it.hasFocus) {
                        viewModel.setBackInProgress(false)
                    }
                }
                .wrapContentWidth()
                .wrapContentHeight(),
            colors = SurfaceDefaults.colors(
                containerColor = Color.Black
            ),
            tonalElevation = 6.dp
        ) {
            LazyColumn(state = listState, modifier = Modifier.selectableGroup()) {
                item(
                    key = "privacy_policy_header",
                    contentType = "privacy_policy_header"
                ) {
                    Text(
                        text = stringResource(R.string.privacy),
                        modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 15.dp),
                        color = Color(0xFF25B9EF),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    HorizontalDivider(
                        thickness = 2.dp,
                        color = White,
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                    )
                }
                item(
                    key = "anonymous_statistics",
                    contentType = "anonymous_statistics"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.anonymous_statistics),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.send_anonymous_statistics_to_improve_app_performance),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = currentConfig.statsEnabled,
                                onCheckedChange = {},
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF25B9EF),
                                    checkedTrackColor = Color(0xFFC0E6EC),
                                    uncheckedThumbColor = Color(0xFF858484),
                                    uncheckedTrackColor = Color(0xFFBDBDBD)
                                )
                            )
                        },
                        onClick = {
                            img = R.drawable.baseline_warning_amber_24
                            dialogTitle.value =
                                resources.getString(R.string.restart_application)
                            dialogText.value = resources.getString(R.string.restart_now)
                            confirmButtonText.value = resources.getString(R.string.yes)
                            dismissButtonText.value = resources.getString(R.string.cancel)
                            isCancelRequired.value = true
                            isStats.value = true
                            openAlertDialog.value = true
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                item(
                    key = "privacy_policy",
                    contentType = "privacy_policy"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_privacy_policy),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_privacy_policy),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        onClick = {
                            viewModel.url = PRIVACY_URL
                            viewModel.getNavController()?.navigate(Screens.HelpScreen.route)
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                item(
                    key = "category_basic_header",
                    contentType = "category_basic_header"
                ) {
                    Text(
                        text = stringResource(R.string.category_basic_settings),
                        modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 15.dp),
                        color = Color(0xFF25B9EF),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    HorizontalDivider(
                        thickness = 2.dp,
                        color = White,
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                    )
                }
                item(
                    key = "resolution_list",
                    contentType = "resolution_list"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_resolution_list),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_resolution_list),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        onClick = {
                            isResolution.value = true
                            isAudioOutput.value = false
                            isAnalog.value = false
                            isVideoFormat.value = false
                            isLanguage.value = false
                            isAudioConfig.value = false
                            isFps.value = false
                            isAaudio.value = false
                            isFramePacing.value = false
                            img = R.drawable.baseline_video_settings_24
                            dialogTitle.value =
                                resources.getString(R.string.title_resolution_list)
                            dialogText.value = resources.getString(R.string.summary_resolution_list)
                            dismissButtonText.value = resources.getString(R.string.cancel)
                            isCancelRequired.value = true
                            buttonListAlertDialog.value = true
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                item(
                    key = "fps_list",
                    contentType = "fps_list"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_fps_list),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_fps_list),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        onClick = {
                            isFps.value = true
                            isAudioOutput.value = false
                            isAnalog.value = false
                            isAaudio.value = false
                            isVideoFormat.value = false
                            isLanguage.value = false
                            isAudioConfig.value = false
                            isResolution.value = false
                            isFramePacing.value = false
                            img = R.drawable.baseline_video_settings_24
                            dialogTitle.value =
                                resources.getString(R.string.title_fps_list)
                            dialogText.value = resources.getString(R.string.summary_fps_list)
                            dismissButtonText.value = resources.getString(R.string.cancel)
                            isCancelRequired.value = true
                            buttonListAlertDialog.value = true

                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                item(
                    key = "seekbar_bitrate",
                    contentType = "seekbar_bitrate"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_seekbar_bitrate),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_seekbar_bitrate),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        onClick = {
                            img = R.drawable.baseline_video_settings_24
                            dialogTitle.value =
                                resources.getString(R.string.title_seekbar_bitrate)
                            dialogText.value = resources.getString(R.string.summary_seekbar_bitrate)
                            dismissButtonText.value = resources.getString(R.string.cancel)
                            confirmButtonText.value = resources.getString(android.R.string.ok)
                            seekBarAlertDialog.value = true
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                item(
                    key = "frame_pacing",
                    contentType = "frame_pacing"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_frame_pacing),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_frame_pacing),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        onClick = {
                            isFramePacing.value = true
                            isAudioOutput.value = false
                            isAnalog.value = false
                            isVideoFormat.value = false
                            isLanguage.value = false
                            isAudioConfig.value = false
                            isResolution.value = false
                            isAaudio.value = false
                            isFps.value = false
                            img = R.drawable.baseline_video_settings_24
                            dialogTitle.value =
                                resources.getString(R.string.title_frame_pacing)
                            dialogText.value = resources.getString(R.string.summary_frame_pacing)
                            dismissButtonText.value = resources.getString(R.string.cancel)
                            isCancelRequired.value = true
                            buttonListAlertDialog.value = true
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                if (currentConfig.glesValue >= 0x30001) {
                    item(
                        key = "video_sr",
                        contentType = "video_sr"
                    ) {
                        ListItem(
                            overlineContent = {
                                Text(
                                    stringResource(R.string.title_video_sr),
                                    modifier = Modifier.padding(bottom = 10.dp),
                                    color = Color(0xFFCAEFF4),
                                    fontSize = 16.sp
                                )
                            },
                            headlineContent = {
                                Text(
                                    stringResource(R.string.summary_video_sr),
                                    color = White,
                                    fontSize = 14.sp
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = currentConfig.videoSr,
                                    onCheckedChange = {},
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF25B9EF),
                                        checkedTrackColor = Color(0xFFC0E6EC),
                                        uncheckedThumbColor = Color(0xFF858484),
                                        uncheckedTrackColor = Color(0xFFBDBDBD)
                                    )
                                )
                            },
                            onClick = {
                                if (currentConfig.videoSr) {
                                   coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[booleanPreferencesKey(VIDEO_SR_STRING)] =
                                                false
                                        }
                                    }
                                } else {
                                   coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[booleanPreferencesKey(VIDEO_SR_STRING)] =
                                                true
                                        }
                                    }
                                }
                            },
                            selected = false,
                            scale = ListItemDefaults.scale(
                                focusedScale = 1f,
                                selectedScale = 1f
                            ),
                            colors = ListItemDefaults.colors(
                                focusedContainerColor = Color(0x6633B5E5),
                                pressedContainerColor = Color.Black,
                                pressedContentColor = White
                            )
                        )
                    }
                }
                item(
                    key = "stretch_video",
                    contentType = "stretch_video"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_stretch_video),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.title_checkbox_stretch_video),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = currentConfig.stretchVideo,
                                onCheckedChange = {},
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF25B9EF),
                                    checkedTrackColor = Color(0xFFC0E6EC),
                                    uncheckedThumbColor = Color(0xFF858484),
                                    uncheckedTrackColor = Color(0xFFBDBDBD)
                                )
                            )
                        },
                        onClick = {
                            if (currentConfig.stretchVideo) {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(STRETCH_PREF_STRING)] =
                                            false
                                    }
                                }
                            } else {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(STRETCH_PREF_STRING)] =
                                            true
                                    }
                                }
                            }
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                item(
                    key = "category_audio_header",
                    contentType = "category_audio_header"
                ) {
                    Text(
                        text = stringResource(R.string.category_audio_settings),
                        modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 15.dp),
                        color = Color(0xFF25B9EF),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    HorizontalDivider(
                        thickness = 2.dp,
                        color = White,
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                    )
                }
                item(
                    key = "audio_config_list",
                    contentType = "audio_config_list"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_audio_config_list),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_audio_config_list),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        onClick = {
                            isAudioConfig.value = true
                            isAudioOutput.value = false
                            isAnalog.value = false
                            isVideoFormat.value = false
                            isLanguage.value = false
                            isFramePacing.value = false
                            isResolution.value = false
                            isAaudio.value = false
                            isFps.value = false
                            img = R.drawable.outline_edit_audio_24
                            dialogTitle.value =
                                resources.getString(R.string.title_audio_config_list)
                            dialogText.value =
                                resources.getString(R.string.summary_audio_config_list)
                            dismissButtonText.value = resources.getString(R.string.cancel)
                            isCancelRequired.value = true
                            buttonListAlertDialog.value = true
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                item(
                    key = "audio_output_list",
                    contentType = "audio_output_list"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_audio_output_mode),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_audio_output_mode),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        onClick = {
                            isAudioOutput.value = true
                            isAudioConfig.value = false
                            isAnalog.value = false
                            isVideoFormat.value = false
                            isLanguage.value = false
                            isFramePacing.value = false
                            isResolution.value = false
                            isAaudio.value = false
                            isFps.value = false
                            img = R.drawable.outline_edit_audio_24
                            dialogTitle.value =
                                resources.getString(R.string.title_audio_output_mode)
                            dialogText.value =
                                resources.getString(R.string.summary_audio_output_mode)
                            dismissButtonText.value = resources.getString(R.string.cancel)
                            isCancelRequired.value = true
                            buttonListAlertDialog.value = true
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                // Only disply this if audioOutputMode is PCM else remove and disable
                if (currentConfig.audioOutputMode.getPreferenceValue() == "pcm") {
                    item(
                        key = "checkbox_aaudio",
                        contentType = "checkbox_aaudio"
                    ) {
                        ListItem(
                            overlineContent = {
                                Text(
                                    stringResource(R.string.aaudio_title),
                                    modifier = Modifier.padding(bottom = 10.dp),
                                    color = Color(0xFFCAEFF4),
                                    fontSize = 16.sp
                                )
                            },
                            headlineContent = {
                                Text(
                                    stringResource(
                                        if (isApiLevelSupported) R.string.aaudio_summary
                                        else R.string.aaudio_summary29
                                    ),
                                    color = White,
                                    fontSize = 14.sp
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = currentConfig.aaudio,
                                    onCheckedChange = {},
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF25B9EF),
                                        checkedTrackColor = Color(0xFFC0E6EC),
                                        uncheckedThumbColor = Color(0xFF858484),
                                        uncheckedTrackColor = Color(0xFFBDBDBD)
                                    )
                                )
                            },
                            onClick = {
                                if (!currentConfig.aaudio) {
                                    img = R.drawable.baseline_warning_amber_24
                                    dialogTitle.value =
                                        resources.getString(R.string.enable_aaudio)
                                    dialogText.value = resources.getString(R.string.aaudio)
                                    confirmButtonText.value = resources.getString(R.string.yes)
                                    dismissButtonText.value = resources.getString(R.string.cancel)
                                    isCancelRequired.value = true
                                    isAaudio.value = true
                                    isAudioOutput.value = false
                                    isResolution.value = false
                                    isAnalog.value = false
                                    isVideoFormat.value = false
                                    isLanguage.value = false
                                    isAudioConfig.value = false
                                    isFps.value = false
                                    isFramePacing.value = false
                                    openAlertDialog.value = true
                                } else {
                                   coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[booleanPreferencesKey(AAUDIO_PREF_STRING)] =
                                                false
                                        }
                                    }
                                }
                            },
                            selected = false,
                            scale = ListItemDefaults.scale(
                                focusedScale = 1f,
                                selectedScale = 1f
                            ),
                            colors = ListItemDefaults.colors(
                                focusedContainerColor = Color(0x6633B5E5),
                                pressedContainerColor = Color.Black,
                                pressedContentColor = White
                            )
                        )
                    }
                } else {
                   coroutineScope.launch(Dispatchers.IO) {
                        context.dataStore.edit { preferences ->
                            preferences[booleanPreferencesKey(AAUDIO_PREF_STRING)] =
                                false
                        }
                    }
                }
                item(
                    key = "enable_audiofx",
                    contentType = "enable_audiofx"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_checkbox_enable_audiofx),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_checkbox_enable_audiofx),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = currentConfig.enableAudioFx,
                                onCheckedChange = {},
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF25B9EF),
                                    checkedTrackColor = Color(0xFFC0E6EC),
                                    uncheckedThumbColor = Color(0xFF858484),
                                    uncheckedTrackColor = Color(0xFFBDBDBD)
                                )
                            )
                        },
                        onClick = {
                            if (currentConfig.enableAudioFx) {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(
                                            ENABLE_AUDIO_FX_PREF_STRING
                                        )] = false
                                    }
                                }
                            } else {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(
                                            ENABLE_AUDIO_FX_PREF_STRING
                                        )] = true
                                    }
                                }
                            }
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                item(
                    key = "category_gamepad_header",
                    contentType = "category_gamepad_header"
                ) {
                    Text(
                        text = stringResource(R.string.category_gamepad_settings),
                        modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 15.dp),
                        color = Color(0xFF25B9EF),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    HorizontalDivider(
                        thickness = 2.dp,
                        color = White,
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                    )
                }
                item(
                    key = "seekbar_deadzone",
                    contentType = "seekbar_deadzone"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_seekbar_deadzone),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_seekbar_deadzone),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        onClick = {
                            img = R.drawable.ic_baseline_videogame_asset_24
                            dialogTitle.value =
                                resources.getString(R.string.title_seekbar_deadzone)
                            dialogText.value =
                                resources.getString(R.string.summary_seekbar_deadzone)
                            dismissButtonText.value = resources.getString(R.string.cancel)
                            confirmButtonText.value = resources.getString(android.R.string.ok)
                            deadZoneDialog.value = true
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                item(
                    key = "checkbox_multi_controller",
                    contentType = "checkbox_multi_controller"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_checkbox_multi_controller),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_checkbox_multi_controller),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = currentConfig.multiController,
                                onCheckedChange = {},
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF25B9EF),
                                    checkedTrackColor = Color(0xFFC0E6EC),
                                    uncheckedThumbColor = Color(0xFF858484),
                                    uncheckedTrackColor = Color(0xFFBDBDBD)
                                )
                            )
                        },
                        onClick = {
                            if (currentConfig.multiController) {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(
                                            MULTI_CONTROLLER_PREF_STRING
                                        )] = false
                                    }
                                }
                            } else {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(
                                            MULTI_CONTROLLER_PREF_STRING
                                        )] = true
                                    }
                                }
                            }
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                if (hasUsbHost) {
                    item(
                        key = "checkbox_usb_driver",
                        contentType = "checkbox_usb_driver"
                    ) {
                        ListItem(
                            overlineContent = {
                                Text(
                                    stringResource(R.string.title_checkbox_xb1_driver),
                                    modifier = Modifier.padding(bottom = 10.dp),
                                    color = Color(0xFFCAEFF4),
                                    fontSize = 16.sp
                                )
                            },
                            headlineContent = {
                                Text(
                                    stringResource(R.string.summary_checkbox_xb1_driver),
                                    color = White,
                                    fontSize = 14.sp
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = currentConfig.usbDriver,
                                    onCheckedChange = {},
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF25B9EF),
                                        checkedTrackColor = Color(0xFFC0E6EC),
                                        uncheckedThumbColor = Color(0xFF858484),
                                        uncheckedTrackColor = Color(0xFFBDBDBD)
                                    )
                                )
                            },
                            onClick = {
                                if (currentConfig.usbDriver) {
                                   coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[booleanPreferencesKey(USB_DRIVER_PREF_SRING)] =
                                                false
                                        }
                                    }
                                } else {
                                   coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[booleanPreferencesKey(USB_DRIVER_PREF_SRING)] =
                                                true
                                        }
                                    }
                                }
                            },
                            selected = false,
                            scale = ListItemDefaults.scale(
                                focusedScale = 1f,
                                selectedScale = 1f
                            ),
                            colors = ListItemDefaults.colors(
                                focusedContainerColor = Color(0x6633B5E5),
                                pressedContainerColor = Color.Black,
                                pressedContentColor = White
                            )
                        )
                    }
                    if (currentConfig.usbDriver) {
                        item(
                            key = "checkbox_usb_bind_all",
                            contentType = "checkbox_usb_bind_all"
                        ) {
                            ListItem(
                                enabled = true,
                                overlineContent = {
                                    Text(
                                        stringResource(R.string.title_checkbox_usb_bind_all),
                                        modifier = Modifier.padding(bottom = 10.dp),
                                        color = Color(0xFFCAEFF4),
                                        fontSize = 16.sp
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        stringResource(R.string.summary_checkbox_usb_bind_all),
                                        color = White,
                                        fontSize = 14.sp
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = currentConfig.bindAllUsb,
                                        onCheckedChange = {},
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color(0xFF25B9EF),
                                            checkedTrackColor = Color(0xFFC0E6EC),
                                            uncheckedThumbColor = Color(0xFF858484),
                                            uncheckedTrackColor = Color(0xFFBDBDBD)
                                        )
                                    )
                                },
                                onClick = {
                                    if (currentConfig.bindAllUsb) {
                                       coroutineScope.launch(Dispatchers.IO) {
                                            context.dataStore.edit { preferences ->
                                                preferences[booleanPreferencesKey(
                                                    BIND_ALL_USB_STRING
                                                )] =
                                                    false
                                            }
                                        }
                                    } else {
                                       coroutineScope.launch(Dispatchers.IO) {
                                            context.dataStore.edit { preferences ->
                                                preferences[booleanPreferencesKey(
                                                    BIND_ALL_USB_STRING
                                                )] =
                                                    true
                                            }
                                        }
                                    }
                                },
                                selected = false,
                                scale = ListItemDefaults.scale(
                                    focusedScale = 1f,
                                    selectedScale = 1f
                                ),
                                colors = ListItemDefaults.colors(
                                    focusedContainerColor = Color(0x6633B5E5),
                                    pressedContainerColor = Color.Black,
                                    pressedContentColor = White
                                )
                            )
                        }
                    }
                }
                item(
                    key = "checkbox_mouse_emulation",
                    contentType = "checkbox_mouse_emulation"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_checkbox_mouse_emulation),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_checkbox_mouse_emulation),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = currentConfig.mouseEmulation,
                                onCheckedChange = {},
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF25B9EF),
                                    checkedTrackColor = Color(0xFFC0E6EC),
                                    uncheckedThumbColor = Color(0xFF858484),
                                    uncheckedTrackColor = Color(0xFFBDBDBD)
                                )
                            )
                        },
                        onClick = {
                            if (currentConfig.mouseEmulation) {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(MOUSE_EMULATION_STRING)] =
                                            false
                                    }
                                }
                            } else {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(MOUSE_EMULATION_STRING)] =
                                            true
                                    }
                                }
                            }
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                if (currentConfig.mouseEmulation) {
                    item(
                        key = "analog_scrolling",
                        contentType = "analog_scrolling"
                    ) {
                        ListItem(
                            enabled = true,
                            overlineContent = {
                                Text(
                                    stringResource(R.string.title_analog_scrolling),
                                    modifier = Modifier.padding(bottom = 10.dp),
                                    color = Color(0xFFCAEFF4),
                                    fontSize = 16.sp
                                )
                            },
                            headlineContent = {
                                Text(
                                    stringResource(R.string.summary_analog_scrolling),
                                    color = White,
                                    fontSize = 14.sp
                                )
                            },
                            onClick = {
                                isAnalog.value = true
                                isAudioOutput.value = false
                                isAudioConfig.value = false
                                isVideoFormat.value = false
                                isLanguage.value = false
                                isFramePacing.value = false
                                isResolution.value = false
                                isFps.value = false
                                isAaudio.value = false
                                img = R.drawable.ic_baseline_videogame_asset_24
                                dialogTitle.value =
                                    resources.getString(R.string.title_analog_scrolling)
                                dialogText.value =
                                    resources.getString(R.string.summary_analog_scrolling)
                                dismissButtonText.value = resources.getString(R.string.cancel)
                                isCancelRequired.value = true
                                buttonListAlertDialog.value = true
                            },
                            selected = false,
                            scale = ListItemDefaults.scale(
                                focusedScale = 1f,
                                selectedScale = 1f
                            ),
                            colors = ListItemDefaults.colors(
                                focusedContainerColor = Color(0x6633B5E5),
                                pressedContainerColor = Color.Black,
                                pressedContentColor = White
                            )
                        )
                    }
                }
                item(
                    key = "checkbox_gamepad_touchpad_as_mouse",
                    contentType = "checkbox_gamepad_touchpad_as_mouse"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_checkbox_gamepad_touchpad_as_mouse),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_checkbox_gamepad_touchpad_as_mouse),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = currentConfig.gamepadTouchpadAsMouse,
                                onCheckedChange = {},
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF25B9EF),
                                    checkedTrackColor = Color(0xFFC0E6EC),
                                    uncheckedThumbColor = Color(0xFF858484),
                                    uncheckedTrackColor = Color(0xFFBDBDBD)
                                )
                            )
                        },
                        onClick = {
                            if (currentConfig.gamepadTouchpadAsMouse) {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(
                                            GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING
                                        )] = false
                                    }
                                }
                            } else {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(
                                            GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING
                                        )] = true
                                    }
                                }
                            }
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                item(
                    key = "category_input_header",
                    contentType = "category_input_header"
                ) {
                    Text(
                        text = stringResource(R.string.category_input_settings),
                        modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 15.dp),
                        color = Color(0xFF25B9EF),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    HorizontalDivider(
                        thickness = 2.dp,
                        color = White,
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                    )
                }
                item(
                    key = "checkbox_mouse_nav_buttons",
                    contentType = "checkbox_mouse_nav_buttons"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_checkbox_mouse_nav_buttons),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_checkbox_mouse_nav_buttons),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = currentConfig.mouseNavButtons,
                                onCheckedChange = {},
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF25B9EF),
                                    checkedTrackColor = Color(0xFFC0E6EC),
                                    uncheckedThumbColor = Color(0xFF858484),
                                    uncheckedTrackColor = Color(0xFFBDBDBD)
                                )
                            )
                        },
                        onClick = {
                            if (currentConfig.mouseNavButtons) {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(MOUSE_NAV_BUTTONS_STRING)] =
                                            false
                                    }
                                }
                            } else {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(MOUSE_NAV_BUTTONS_STRING)] =
                                            true
                                    }
                                }
                            }
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                if (!isShield) {
                    item(
                        key = "checkbox_absolute_mouse_mode",
                        contentType = "checkbox_absolute_mouse_mode"
                    ) {
                        ListItem(
                            overlineContent = {
                                Text(
                                    stringResource(R.string.title_checkbox_absolute_mouse_mode),
                                    modifier = Modifier.padding(bottom = 10.dp),
                                    color = Color(0xFFCAEFF4),
                                    fontSize = 16.sp
                                )
                            },
                            headlineContent = {
                                Text(
                                    stringResource(R.string.summary_checkbox_absolute_mouse_mode),
                                    color = White,
                                    fontSize = 14.sp
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = currentConfig.absoluteMouseMode,
                                    onCheckedChange = {},
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF25B9EF),
                                        checkedTrackColor = Color(0xFFC0E6EC),
                                        uncheckedThumbColor = Color(0xFF858484),
                                        uncheckedTrackColor = Color(0xFFBDBDBD)
                                    )
                                )
                            },
                            onClick = {
                                if (currentConfig.absoluteMouseMode) {
                                   coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[booleanPreferencesKey(
                                                ABSOLUTE_MOUSE_MODE_PREF_STRING
                                            )] = false
                                        }
                                    }
                                } else {
                                   coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[booleanPreferencesKey(
                                                ABSOLUTE_MOUSE_MODE_PREF_STRING
                                            )] = true
                                        }
                                    }
                                }
                            },
                            selected = false,
                            scale = ListItemDefaults.scale(
                                focusedScale = 1f,
                                selectedScale = 1f
                            ),
                            colors = ListItemDefaults.colors(
                                focusedContainerColor = Color(0x6633B5E5),
                                pressedContainerColor = Color.Black,
                                pressedContentColor = White
                            )
                        )
                    }
                }
                item(
                    key = "category_ui_header",
                    contentType = "category_ui_header"
                ) {
                    Text(
                        text = stringResource(R.string.category_ui_settings),
                        modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 15.dp),
                        color = Color(0xFF25B9EF),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    HorizontalDivider(
                        thickness = 2.dp,
                        color = White,
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                    )
                }
                if (!isFireOS && hasPip) {
                    item(
                        key = "checkbox_enable_pip",
                        contentType = "checkbox_enable_pip"
                    ) {
                        ListItem(
                            overlineContent = {
                                Text(
                                    stringResource(R.string.title_checkbox_enable_pip),
                                    modifier = Modifier.padding(bottom = 10.dp),
                                    color = Color(0xFFCAEFF4),
                                    fontSize = 16.sp
                                )
                            },
                            headlineContent = {
                                Text(
                                    stringResource(R.string.summary_checkbox_enable_pip),
                                    color = White,
                                    fontSize = 14.sp
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = currentConfig.enablePip,
                                    onCheckedChange = {},
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF25B9EF),
                                        checkedTrackColor = Color(0xFFC0E6EC),
                                        uncheckedThumbColor = Color(0xFF858484),
                                        uncheckedTrackColor = Color(0xFFBDBDBD)
                                    )
                                )
                            },
                            onClick = {
                                if (currentConfig.enablePip) {
                                   coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[booleanPreferencesKey(ENABLE_PIP_PREF_STRING)] =
                                                false
                                        }
                                    }
                                } else {
                                   coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[booleanPreferencesKey(ENABLE_PIP_PREF_STRING)] =
                                                true
                                        }
                                    }
                                }
                            },
                            selected = false,
                            scale = ListItemDefaults.scale(
                                focusedScale = 1f,
                                selectedScale = 1f
                            ),
                            colors = ListItemDefaults.colors(
                                focusedContainerColor = Color(0x6633B5E5),
                                pressedContainerColor = Color.Black,
                                pressedContentColor = White
                            )
                        )
                    }
                }
                item(
                    key = "captions",
                    contentType = "captions"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.captions),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.captions_text),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = currentConfig.areCaptionsVisible,
                                onCheckedChange = {},
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF25B9EF),
                                    checkedTrackColor = Color(0xFFC0E6EC),
                                    uncheckedThumbColor = Color(0xFF858484),
                                    uncheckedTrackColor = Color(0xFFBDBDBD)
                                )
                            )
                        },
                        onClick = {
                            if (currentConfig.areCaptionsVisible) {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(
                                            DEFAULT_CAPTIONS_STRING
                                        )] = false
                                    }
                                }
                            } else {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(
                                            DEFAULT_CAPTIONS_STRING
                                        )] = true
                                    }
                                }
                            }
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                item(
                    key = "language_list",
                    contentType = "language_list"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_language_list),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_language_list),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        onClick = {
                            isLanguage.value = true
                            isAudioOutput.value = false
                            isAnalog.value = false
                            isVideoFormat.value = false
                            isAudioConfig.value = false
                            isFramePacing.value = false
                            isResolution.value = false
                            isFps.value = false
                            isAaudio.value = false
                            img = R.drawable.outline_language_24
                            dialogTitle.value =
                                resources.getString(R.string.title_language_list)
                            dialogText.value = resources.getString(R.string.summary_language_list)
                            dismissButtonText.value = resources.getString(R.string.cancel)
                            isCancelRequired.value = true
                            buttonListAlertDialog.value = true
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                if (!isLanguageDefault && !currentConfig.mlDownloaded) {
                    item(
                        key = "language_model",
                        contentType = "language_model"
                    ) {
                        ListItem(
                            overlineContent = {
                                Text(
                                    stringResource(R.string.download_translations_model),
                                    modifier = Modifier.padding(bottom = 10.dp),
                                    color = Color(0xFFCAEFF4),
                                    fontSize = 16.sp
                                )
                            },
                            headlineContent = {
                                Text(
                                    stringResource(R.string.download_locale_language_model_to_translate_game_data),
                                    color = White,
                                    fontSize = 14.sp
                                )
                            },
                            trailingContent = {
                                Image(
                                    painter = painterResource(id = R.drawable.baseline_download_24_small),
                                    colorFilter = ColorFilter.tint(Color(0xFF25B9EF)),
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                img = R.drawable.baseline_download_24_small
                                dialogTitle.value =
                                    resources.getString(R.string.download_translations_model)
                                dialogText.value =
                                    resources.getString(R.string.download_locale_language_model_to_translate_game_data)
                                viewModel.setLoaderValue(true)
                            },
                            selected = false,
                            scale = ListItemDefaults.scale(
                                focusedScale = 1f,
                                selectedScale = 1f
                            ),
                            colors = ListItemDefaults.colors(
                                focusedContainerColor = Color(0x6633B5E5),
                                pressedContainerColor = Color.Black,
                                pressedContentColor = White
                            )
                        )
                    }
                }
                if (currentConfig.mlDownloaded) {
                    item(
                        key = "delete_model",
                        contentType = "delete_model"
                    ) {
                        ListItem(
                            overlineContent = {
                                Text(
                                    stringResource(R.string.delete_language_model),
                                    modifier = Modifier.padding(bottom = 10.dp),
                                    color = Color(0xFFCAEFF4),
                                    fontSize = 16.sp
                                )
                            },
                            headlineContent = {
                                Text(
                                    stringResource(R.string.delete_downloaded_language_model),
                                    color = White,
                                    fontSize = 14.sp
                                )
                            },
                            trailingContent = {
                                Image(
                                    painter = painterResource(id = R.drawable.baseline_delete_forever_24),
                                    colorFilter = ColorFilter.tint(Color(0xFF25B9EF)),
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                val modelManager = RemoteModelManager.getInstance()
                                val translateRemoteModel =
                                    TranslateRemoteModel.Builder(currentConfig.language).build()
                                modelManager.deleteDownloadedModel(translateRemoteModel)
                                    .addOnSuccessListener {

                                        Toast.makeText(
                                            activity,
                                            activity!!.getString(R.string.model_deleted_successfully),
                                            Toast.LENGTH_SHORT
                                        ).show()

                                        viewModel.triggerReload()
                                       coroutineScope.launch(Dispatchers.IO) {
                                            context.dataStore.edit { preferences ->
                                                preferences[booleanPreferencesKey(ML_DOWNLOADED)] =
                                                    false
                                            }
                                        }
                                    }
                                    .addOnFailureListener {
                                        Toast.makeText(
                                            activity,
                                            activity!!.getString(R.string.model_delete_failed_please_try_again),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                            },
                            selected = false,
                            scale = ListItemDefaults.scale(
                                focusedScale = 1f,
                                selectedScale = 1f
                            ),
                            colors = ListItemDefaults.colors(
                                focusedContainerColor = Color(0x6633B5E5),
                                pressedContainerColor = Color.Black,
                                pressedContentColor = White
                            )
                        )
                    }
                }
                if (!currentConfig.leanback) {
                    item(
                        key = "checkbox_small_icon_mode",
                        contentType = "checkbox_small_icon_mode"
                    ) {
                        ListItem(
                            overlineContent = {
                                Text(
                                    stringResource(R.string.title_checkbox_small_icon_mode),
                                    modifier = Modifier.padding(bottom = 10.dp),
                                    color = Color(0xFFCAEFF4),
                                    fontSize = 16.sp
                                )
                            },
                            headlineContent = {
                                Text(
                                    stringResource(R.string.summary_checkbox_small_icon_mode),
                                    color = White,
                                    fontSize = 14.sp
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = currentConfig.smallIconMode,
                                    onCheckedChange = {},
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF25B9EF),
                                        checkedTrackColor = Color(0xFFC0E6EC),
                                        uncheckedThumbColor = Color(0xFF858484),
                                        uncheckedTrackColor = Color(0xFFBDBDBD)
                                    )
                                )
                            },
                            onClick = {
                                if (currentConfig.smallIconMode) {
                                   coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[booleanPreferencesKey(
                                                SMALL_ICONS_PREF_STRING
                                            )] = false
                                        }
                                    }
                                } else {
                                   coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[booleanPreferencesKey(
                                                SMALL_ICONS_PREF_STRING
                                            )] = true
                                        }
                                    }
                                }
                                viewModel.triggerReload()
                            },
                            selected = false,
                            scale = ListItemDefaults.scale(
                                focusedScale = 1f,
                                selectedScale = 1f
                            ),
                            colors = ListItemDefaults.colors(
                                focusedContainerColor = Color(0x6633B5E5),
                                pressedContainerColor = Color.Black,
                                pressedContentColor = White
                            )
                        )
                    }
                }
                if (!isMainView) {
                    item(
                        key = "checkbox_leanback",
                        contentType = "checkbox_leanback"
                    ) {
                        ListItem(
                            overlineContent = {
                                Text(
                                    stringResource(R.string.leanback),
                                    modifier = Modifier.padding(bottom = 10.dp),
                                    color = Color(0xFFCAEFF4),
                                    fontSize = 16.sp
                                )
                            },
                            headlineContent = {
                                Text(
                                    stringResource(R.string.leanback_summary),
                                    color = White,
                                    fontSize = 14.sp
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = currentConfig.leanback,
                                    onCheckedChange = {},
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF25B9EF),
                                        checkedTrackColor = Color(0xFFC0E6EC),
                                        uncheckedThumbColor = Color(0xFF858484),
                                        uncheckedTrackColor = Color(0xFFBDBDBD)
                                    )
                                )
                            },
                            onClick = {
                                if (currentConfig.leanback) {
                                   coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[booleanPreferencesKey(LEANBACK_PREF_STRING)] =
                                                false
                                        }
                                    }
                                } else {
                                   coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[booleanPreferencesKey(LEANBACK_PREF_STRING)] =
                                                true
                                        }
                                    }
                                }
                                viewModel.triggerReload()
                            },
                            selected = false,
                            scale = ListItemDefaults.scale(
                                focusedScale = 1f,
                                selectedScale = 1f
                            ),
                            colors = ListItemDefaults.colors(
                                focusedContainerColor = Color(0x6633B5E5),
                                pressedContainerColor = Color.Black,
                                pressedContentColor = White
                            )
                        )
                    }
                }
                item(
                    key = "category_advanced_settings",
                    contentType = "category_advanced_settings"
                ) {
                    Text(
                        text = stringResource(R.string.category_advanced_settings),
                        modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 15.dp),
                        color = Color(0xFF25B9EF),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    HorizontalDivider(
                        thickness = 2.dp,
                        color = White,
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                    )
                }
                item(
                    key = "checkbox_unlock_fps",
                    contentType = "checkbox_unlock_fps"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_unlock_fps),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_unlock_fps),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = currentConfig.unlockFps,
                                onCheckedChange = {},
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF25B9EF),
                                    checkedTrackColor = Color(0xFFC0E6EC),
                                    uncheckedThumbColor = Color(0xFF858484),
                                    uncheckedTrackColor = Color(0xFFBDBDBD)
                                )
                            )
                        },
                        onClick = {
                            if (currentConfig.unlockFps) {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(UNLOCK_FPS_STRING)] =
                                            false
                                    }
                                }
                            } else {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(UNLOCK_FPS_STRING)] = true
                                    }
                                }
                            }
                            viewModel.triggerReload()
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                item(
                    key = "checkbox_reduce_refresh_rate",
                    contentType = "checkbox_reduce_refresh_rate"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_checkbox_reduce_refresh_rate),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_checkbox_reduce_refresh_rate),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = currentConfig.reduceRefreshRate,
                                onCheckedChange = {},
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF25B9EF),
                                    checkedTrackColor = Color(0xFFC0E6EC),
                                    uncheckedThumbColor = Color(0xFF858484),
                                    uncheckedTrackColor = Color(0xFFBDBDBD)
                                )
                            )
                        },
                        onClick = {
                            if (currentConfig.reduceRefreshRate) {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(
                                            REDUCE_REFRESH_RATE_PREF_STRING
                                        )] = false
                                    }
                                }
                            } else {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(
                                            REDUCE_REFRESH_RATE_PREF_STRING
                                        )] = true
                                    }
                                }
                            }
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                item(
                    key = "checkbox_disable_warnings",
                    contentType = "checkbox_disable_warnings"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_checkbox_disable_warnings),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_checkbox_disable_warnings),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = currentConfig.disableWarnings,
                                onCheckedChange = {},
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF25B9EF),
                                    checkedTrackColor = Color(0xFFC0E6EC),
                                    uncheckedThumbColor = Color(0xFF858484),
                                    uncheckedTrackColor = Color(0xFFBDBDBD)
                                )
                            )
                        },
                        onClick = {
                            if (currentConfig.disableWarnings) {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(DISABLE_TOASTS_PREF_STRING)] =
                                            false
                                    }
                                }
                            } else {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(DISABLE_TOASTS_PREF_STRING)] =
                                            true
                                    }
                                }
                            }
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                item(
                    key = "video_format",
                    contentType = "video_format"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_video_format),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_video_format),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        onClick = {
                            isVideoFormat.value = true
                            isAudioOutput.value = false
                            isAnalog.value = false
                            isResolution.value = false
                            isLanguage.value = false
                            isAudioConfig.value = false
                            isFps.value = false
                            isFramePacing.value = false
                            isAaudio.value = false
                            img = R.drawable.baseline_video_settings_24
                            dialogTitle.value =
                                resources.getString(R.string.title_video_format)
                            dialogText.value = resources.getString(R.string.summary_video_format)
                            dismissButtonText.value = resources.getString(R.string.cancel)
                            isCancelRequired.value = true
                            buttonListAlertDialog.value = true
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                item(
                    key = "checkbox_full_range",
                    contentType = "checkbox_full_range"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_full_range),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_full_range),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = currentConfig.fullRange,
                                onCheckedChange = {},
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF25B9EF),
                                    checkedTrackColor = Color(0xFFC0E6EC),
                                    uncheckedThumbColor = Color(0xFF858484),
                                    uncheckedTrackColor = Color(0xFFBDBDBD)
                                )
                            )
                        },
                        onClick = {
                            if (currentConfig.fullRange) {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(FULL_RANGE_PREF_STRING)] =
                                            false
                                    }
                                }
                            } else {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(FULL_RANGE_PREF_STRING)] =
                                            true
                                    }
                                }
                            }
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                item(
                    key = "checkbox_enable_perf_overlay",
                    contentType = "checkbox_enable_perf_overlay"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_enable_perf_overlay),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_enable_perf_overlay),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = currentConfig.enablePerfOverlay,
                                onCheckedChange = {},
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF25B9EF),
                                    checkedTrackColor = Color(0xFFC0E6EC),
                                    uncheckedThumbColor = Color(0xFF858484),
                                    uncheckedTrackColor = Color(0xFFBDBDBD)
                                )
                            )
                        },
                        onClick = {
                            if (currentConfig.enablePerfOverlay) {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(ENABLE_PERF_OVERLAY_STRING)] =
                                            false
                                    }
                                }
                            } else {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(ENABLE_PERF_OVERLAY_STRING)] =
                                            true
                                    }
                                }
                            }
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
                item(
                    key = "checkbox_enable_post_stream_toast",
                    contentType = "checkbox_enable_post_stream_toast"
                ) {
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(R.string.title_enable_post_stream_toast),
                                modifier = Modifier.padding(bottom = 10.dp),
                                color = Color(0xFFCAEFF4),
                                fontSize = 16.sp
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.summary_enable_post_stream_toast),
                                color = White,
                                fontSize = 14.sp
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = currentConfig.enableLatencyToast,
                                onCheckedChange = {},
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF25B9EF),
                                    checkedTrackColor = Color(0xFFC0E6EC),
                                    uncheckedThumbColor = Color(0xFF858484),
                                    uncheckedTrackColor = Color(0xFFBDBDBD)
                                )
                            )
                        },
                        onClick = {
                            if (currentConfig.enableLatencyToast) {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(LATENCY_TOAST_PREF_STRING)] =
                                            false
                                    }
                                }
                            } else {
                               coroutineScope.launch(Dispatchers.IO) {
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey(LATENCY_TOAST_PREF_STRING)] =
                                            true
                                    }
                                }
                            }
                        },
                        selected = false,
                        scale = ListItemDefaults.scale(
                            focusedScale = 1f,
                            selectedScale = 1f
                        ),
                        colors = ListItemDefaults.colors(
                            focusedContainerColor = Color(0x6633B5E5),
                            pressedContainerColor = Color.Black,
                            pressedContentColor = White
                        )
                    )
                }
            }
            when {
                showLoader -> {
                    dialogText.value.let {
                        img?.let { icon ->
                            DownloadDialog(
                                onDismissRequest = { /* Dialog will close when done */ },
                                title = dialogTitle.value,
                                message = it,
                                icon = icon,
                                viewModel = viewModel
                            )
                        }
                    }
                }
            }
            when {
                openAlertDialog.value -> {
                    dialogText.value.let {
                        img?.let { icon ->
                            AlertDialog(
                                onDismissRequest = {
                                    if (isStats.value) {
                                        openAlertDialog.value = false
                                        isStats.value = false
                                    } else if (isHelp.value) {
                                        viewModel.url = TROUBLESHOOTING_URL
                                        viewModel.getNavController()
                                            ?.navigate(Screens.HelpScreen.route)
                                        openAlertDialog.value = false
                                        isHelp.value = false
                                    } else if (isLanguage.value) {
                                        openAlertDialog.value = false
                                        isLanguage.value = false
                                    } else if (isAaudio.value) {
                                        openAlertDialog.value = false
                                        isAaudio.value = false
                                    }
                                },
                                onConfirmation = {
                                    if (isStats.value) {
                                       coroutineScope.launch(Dispatchers.IO) {
                                            context.dataStore.edit { preferences ->
                                                preferences[booleanPreferencesKey(STATS_PREF_STRING)] =
                                                    !currentConfig.statsEnabled
                                            }
                                        }
                                        restartApplication(context)
                                        openAlertDialog.value = false
                                        isStats.value = false
                                    } else if (!isFpsSupported.value) {
                                       coroutineScope.launch(Dispatchers.IO) {
                                            context.dataStore.edit { preferences ->
                                                preferences[stringPreferencesKey(FPS_PREF_STRING)] =
                                                    unsupportedFps.value
                                            }
                                        }
                                        openAlertDialog.value = false
                                        isFpsSupported.value = true
                                        resetBitrateToDefault(
                                            context = context,
                                            coroutineScope = coroutineScope,
                                            resolutionValue = currentConfig.resolution,
                                            fpsValue = unsupportedFps.value
                                        )
                                    } else if (isLanguage.value) {
                                       coroutineScope.launch(Dispatchers.IO) {
                                            context.dataStore.edit { preferences ->
                                                preferences[stringPreferencesKey(
                                                    LANGUAGE_PREF_STRING
                                                )] = if (selectedLanguage.value == "default") DEFAULT_LANGUAGE_STRING else selectedLanguage.value
                                            }
                                        }
                                        restartApplication(context)
                                        openAlertDialog.value = false
                                        isLanguage.value = false
                                    } else if (isAaudio.value) {
                                       coroutineScope.launch(Dispatchers.IO) {
                                            context.dataStore.edit { preferences ->
                                                preferences[booleanPreferencesKey(AAUDIO_PREF_STRING)] =
                                                    !currentConfig.aaudio
                                            }
                                        }
                                        openAlertDialog.value = false
                                        isAaudio.value = false
                                    }
                                },
                                dialogTitle = dialogTitle.value,
                                dialogText = it,
                                icon = icon,
                                confirm = confirmButtonText.value,
                                dismiss = dismissButtonText.value,
                                neutral = "",
                                isNeutralRequired = false,
                                isCancelRequired = isCancelRequired.value,
                                onNeutralRequest = { /* Nothing to do here */ },
                                dismissOnBackPress = true
                            )
                        }
                    }
                }
            }
            when {
                buttonListAlertDialog.value -> {
                    dialogText.value.let {
                        img?.let { icon ->
                            ButtonListAlertDialog(
                                onDismissRequest = {
                                    buttonListAlertDialog.value = false
                                },
                                onClick = {
                                    if (isResolution.value) {
                                       coroutineScope.launch(Dispatchers.IO) {
                                            context.dataStore.edit { preferences ->
                                                preferences[stringPreferencesKey(
                                                    RESOLUTION_PREF_STRING
                                                )] = it
                                            }
                                        }
                                        buttonListAlertDialog.value = false
                                        resetBitrateToDefault(
                                            context = context,
                                            coroutineScope = coroutineScope,
                                            resolutionValue = it,
                                            fpsValue = currentConfig.fps.toString()

                                        )
                                    } else if (isFps.value) {
                                        if (currentConfig.unlockFps) {
                                            val isNativeSupported =
                                                supportedFpsOptions.any { opt -> opt.second == it }
                                            if (!isNativeSupported) {
                                                img = R.drawable.baseline_warning_amber_24
                                                dialogTitle.value =
                                                    resources.getString(R.string.title_native_fps_dialog)
                                                dialogText.value =
                                                    resources.getString(R.string.text_native_res_dialog)
                                                dismissButtonText.value =
                                                    resources.getString(R.string.help)
                                                confirmButtonText.value =
                                                    resources.getString(android.R.string.ok)
                                                isCancelRequired.value = true
                                                unsupportedFps.value = it
                                                isHelp.value = true
                                                buttonListAlertDialog.value = false
                                                openAlertDialog.value = true
                                                isFpsSupported.value = false
                                            } else {
                                               coroutineScope.launch(Dispatchers.IO) {
                                                    context.dataStore.edit { preferences ->
                                                        preferences[stringPreferencesKey(
                                                            FPS_PREF_STRING
                                                        )] = it
                                                    }
                                                }
                                                buttonListAlertDialog.value = false
                                                resetBitrateToDefault(
                                                    context = context,
                                                    coroutineScope = coroutineScope,
                                                    resolutionValue = currentConfig.resolution,
                                                    fpsValue = it
                                                )
                                            }
                                        } else {
                                           coroutineScope.launch(Dispatchers.IO) {
                                                context.dataStore.edit { preferences ->
                                                    preferences[stringPreferencesKey(FPS_PREF_STRING)] =
                                                        it
                                                }
                                            }
                                            buttonListAlertDialog.value = false
                                            resetBitrateToDefault(
                                                context = context,
                                                coroutineScope = coroutineScope,
                                                resolutionValue = currentConfig.resolution,
                                                fpsValue = it
                                            )
                                        }
                                    } else if (isFramePacing.value) {
                                       coroutineScope.launch(Dispatchers.IO) {
                                            context.dataStore.edit { preferences ->
                                                preferences[stringPreferencesKey(
                                                    FRAME_PACING_PREF_STRING
                                                )] = it
                                            }
                                        }
                                        buttonListAlertDialog.value = false
                                    } else if (isAudioConfig.value) {
                                       coroutineScope.launch(Dispatchers.IO) {
                                            context.dataStore.edit { preferences ->
                                                preferences[stringPreferencesKey(
                                                    AUDIO_CONFIG_PREF_STRING
                                                )] = it
                                            }
                                        }
                                        buttonListAlertDialog.value = false
                                    } else if (isLanguage.value) {
                                        img = R.drawable.baseline_restart_alt_24
                                        dialogTitle.value =
                                            resources.getString(R.string.restart_application)
                                        dialogText.value =
                                            resources.getString(R.string.restart_now)
                                        dismissButtonText.value =
                                            resources.getString(R.string.cancel)
                                        confirmButtonText.value =
                                            resources.getString(android.R.string.ok)
                                        isCancelRequired.value = true
                                        selectedLanguage.value = it
                                        buttonListAlertDialog.value = false
                                        openAlertDialog.value = true
                                    } else if (isVideoFormat.value) {
                                       coroutineScope.launch(Dispatchers.IO) {
                                            context.dataStore.edit { preferences ->
                                                preferences[stringPreferencesKey(
                                                    VIDEO_FORMAT_PREF_STRING
                                                )] = it
                                            }
                                        }
                                        buttonListAlertDialog.value = false
                                    } else if (isAnalog.value) {
                                       coroutineScope.launch(Dispatchers.IO) {
                                            context.dataStore.edit { preferences ->
                                                preferences[stringPreferencesKey(
                                                    DEFAULT_ANALOG_STICK_FOR_SCROLLING
                                                )] = it
                                            }
                                        }
                                        buttonListAlertDialog.value = false
                                    } else if (isAudioOutput.value) {
                                       coroutineScope.launch(Dispatchers.IO) {
                                            context.dataStore.edit { preferences ->
                                                preferences[stringPreferencesKey(
                                                    AUDIO_OUTPUT_MODE_PREF_STRING
                                                )] = it
                                            }
                                        }
                                        buttonListAlertDialog.value = false
                                    }
                                },
                                dialogTitle = dialogTitle.value,
                                icon = icon,
                                confirm = "",
                                dismiss = dismissButtonText.value,
                                neutral = "",
                                isNeutralRequired = false,
                                isCancelRequired = true,
                                isPositiveRequired = false,
                                onNeutralRequest = { /* Nothing to do here */ },
                                entryNames = if (isResolution.value) {
                                    supportedResolutions.map { it.first }
                                } else if (isFps.value) {
                                    supportedFpsOptions.map { it.first }
                                } else if (isFramePacing.value) {
                                    framePacingNames.toList()
                                } else if (isAudioConfig.value) {
                                    audioNames.toList()
                                } else if (isLanguage.value) {
                                    languageNames.toList()
                                } else if (isVideoFormat.value) {
                                    videoFormatNames.toList()
                                } else if (isAnalog.value) {
                                    analogNames.toList()
                                } else if (isAudioOutput.value) {
                                    audioOutputNames.toList()
                                } else {
                                    emptyList()
                                },
                                entryValues = if (isResolution.value) {
                                    supportedResolutions.map { it.second }
                                } else if (isFps.value) {
                                    supportedFpsOptions.map { it.second }
                                } else if (isFramePacing.value) {
                                    framePacingValues.toList()
                                } else if (isAudioConfig.value) {
                                    audioValues.toList()
                                } else if (isLanguage.value) {
                                    languageValues.toList()
                                } else if (isVideoFormat.value) {
                                    videoFormatValues.toList()
                                } else if (isAnalog.value) {
                                    analogValues.toList()
                                } else if (isAudioOutput.value) {
                                    audioOutputValues.toList()
                                } else {
                                    emptyList()
                                },
                                initialSelection = if (isResolution.value) {
                                    currentConfig.resolution
                                } else if (isFps.value) {
                                    currentConfig.fps.toString()
                                } else if (isFramePacing.value) {
                                    currentConfig.framePacingString
                                } else if (isAudioConfig.value) {
                                    currentConfig.audioConfig
                                } else if (isLanguage.value) {
                                    currentConfig.language
                                } else if (isVideoFormat.value) {
                                    currentConfig.videoFormatConfig
                                } else if (isAnalog.value) {
                                    currentConfig.analogScrolling
                                } else if (isAudioOutput.value) {
                                    currentConfig.audioOutputMode.getPreferenceValue()
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }
            }
            when {
                seekBarAlertDialog.value -> {
                    dialogText.value.let {
                        img?.let { icon ->
                            SeekBarDialog(
                                onDismiss = {
                                    seekBarAlertDialog.value = false
                                },
                                onConfirm = {
                                   coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[intPreferencesKey(BITRATE_PREF_STRING)] = it
                                        }
                                    }
                                    seekBarAlertDialog.value = false
                                },
                                dialogTitle = dialogTitle.value,
                                icon = icon,
                                confirm = confirmButtonText.value,
                                dismiss = dismissButtonText.value,
                                currentPrefValue = currentConfig.bitrate
                            )
                        }
                    }
                }
            }
            when {
                deadZoneDialog.value -> {
                    dialogText.value.let {
                        img?.let { icon ->
                            DeadZoneDialog(
                                onDismiss = {
                                    deadZoneDialog.value = false
                                },
                                onConfirm = {
                                   coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[intPreferencesKey(DEADZONE_PREF_STRING)] =
                                                it
                                        }
                                    }
                                    deadZoneDialog.value = false
                                },
                                dialogTitle = dialogTitle.value,
                                icon = icon,
                                confirm = confirmButtonText.value,
                                dismiss = dismissButtonText.value,
                                currentPrefValue = currentConfig.deadzonePercentage
                            )
                        }
                    }
                }
            }
        }
    }
}

// Helper Function to Restart Application
private fun restartApplication(context: Context) {
    val packageManager = context.packageManager
    val intent = packageManager.getLaunchIntentForPackage(context.packageName)
    if (intent != null) {
        val restartIntent = Intent.makeRestartActivityTask(intent.component)
        context.startActivity(restartIntent)
        exitProcess(0)
    }
}

// Helper Function to Reset Bitrate
private fun resetBitrateToDefault(
    context: Context,
    coroutineScope: CoroutineScope,
    resolutionValue: String,
    fpsValue: String,
) {

   coroutineScope.launch {
        context.dataStore.edit { preferences ->
            preferences[intPreferencesKey(name = BITRATE_PREF_STRING)] =
                PreferenceKeys.calculateBitrate(resString = resolutionValue, fpsString = fpsValue)
        }
    }
}
