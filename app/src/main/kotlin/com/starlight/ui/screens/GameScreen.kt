package com.starlight.ui.screens

import android.content.Context.BIND_AUTO_CREATE
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.preferKeepClear
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.starlight.R
import com.starlight.binding.audio.AdaptiveAudioRenderer
import com.starlight.binding.audio.AudioRendererFactory
import com.starlight.binding.input.driver.UsbDriverService
import com.starlight.computers.ComputerManagerService
import com.starlight.models.MainViewModel
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.NvApp
import com.starlight.preferences.PreferenceKeys
import com.starlight.preferences.PreferenceKeys.dataStoreConfigFlow
import com.starlight.ui.dialogs.AlertDialog
import com.starlight.ui.dialogs.ProgressDialog
import com.starlight.ui.helpers.GameScreenHelper
import com.starlight.ui.helpers.ShortcutHelper
import com.starlight.ui.helpers.UiHelper
import com.starlight.ui.modifiers.handleGameBackEvent
import com.starlight.ui.modifiers.onRemoteKey
import com.starlight.ui.streamview.StreamView
import com.starlight.ui.themes.StarlightTheme
import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.warning
import kotlinx.coroutines.launch

/**
 * Created by Kevin Andrews on 14/09/2026.
 */

@Composable
fun GameScreen(
    viewModel: MainViewModel,
    appId: String,
    appName: String,
    computer: ComputerDetails,
    binder: ComputerManagerService.ComputerManagerBinder?,
    uuid: String,
    isHdrSupported: Boolean,
    pcName: String,
    uniqueIdString: String,
    cert: ByteArray?,
    app: NvApp,
) {

    val activity = LocalActivity.current as ComponentActivity
    val context = LocalContext.current
    val shortcutHelper = remember { ShortcutHelper(activity) }
    val config by context.dataStoreConfigFlow.collectAsStateWithLifecycle(initialValue = null)

    config?.let { currentConfig ->

        val gameStreamHelper = remember {
            GameScreenHelper(
                viewModel = viewModel,
                activity = activity,
                dataStoreConfiguration = currentConfig,
                appId = appId,
                appName = appName,
                computer = computer,
                binder = binder!!,
                uuid = uuid,
                isHdrSupported = isHdrSupported,
                pcName = pcName,
                uniqueIdString = uniqueIdString,
                cert = cert,
                app = app,
                shortcutHelper = shortcutHelper
            )
        }

        val showLoader by viewModel.showProgressDialog.observeAsState(false)
        val showDialog by viewModel.openAlertDialog.observeAsState(false)
        val isQuitting by viewModel.isQuitting.observeAsState(false)
        val isCancelRequired by viewModel.isCancelRequired.observeAsState(false)
        val isNeutralRequired by viewModel.isNeutralRequired.observeAsState(false)
        val message by viewModel.dialogMessage.observeAsState("")
        val title by viewModel.dialogTitle.observeAsState("")
        val cancelButtonLabel by viewModel.cancelButtonText.observeAsState("")
        val confirmButtonLabel by viewModel.confirmButtonText.observeAsState("")
        val neutralButtonLabel by viewModel.neutralButtonText.observeAsState("")
        val icon by viewModel.image.observeAsState(R.drawable.baseline_error_outline_24)
        val progressMessage by viewModel.progressMessage.observeAsState("")
        val error by viewModel.isErrorState.observeAsState(false)
        val perfOverlay by viewModel.perfOverlay.observeAsState(false)
        val perfOverlayText by viewModel.perfOverlayText.observeAsState("")
        val noticeOverlay by viewModel.notificationOverlay.observeAsState(false)
        val noticeOverlayText by viewModel.notificationOverlayText.observeAsState("")
        val coroutineScope = rememberCoroutineScope()
        val lifecycleOwner = LocalLifecycleOwner.current
        val tvHelper by viewModel.tvHelper.observeAsState()
        val view = LocalView.current
        val streamFocusRequester = remember { FocusRequester() }

        gameStreamHelper.dataStoreConfiguration = currentConfig

        viewModel.setPerfOverlay(currentConfig.enablePerfOverlay)

        LaunchedEffect(lifecycleOwner) {
            streamFocusRequester.requestFocus()
        }

        DisposableEffect(activity) {
            val listener = Consumer<PictureInPictureModeChangedInfo> { _ ->
                gameStreamHelper.handlePictureInPictureModeChanges()
            }
            activity.addOnPictureInPictureModeChangedListener(listener)
            onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
        }

        DisposableEffect(activity) {
            val listener = Consumer<Configuration> { _ ->
                gameStreamHelper.setPreferredOrientationForCurrentDisplay()
                gameStreamHelper.virtualController?.refreshLayout()
            }
            activity.addOnConfigurationChangedListener(listener)
            onDispose { activity.removeOnConfigurationChangedListener(listener) }
        }

        DisposableEffect(view) {
            val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                gameStreamHelper.modifierFlags = 0
                gameStreamHelper.inputCaptureProvider?.onWindowFocusChanged(hasFocus)
            }
            view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
        }

        // Unbind the TvHelperService if launched from the Home channel launcher
        SideEffect (lifecycleOwner) {
            if (tvHelper?.serviceConnection != null) {
                activity.unbindService(tvHelper?.serviceConnection!!)
                info("TvHelperService unbound")
            }
        }

        DisposableEffect(key1 = lifecycleOwner) {
            val bound = if (currentConfig.usbDriver) {
                try {
                    activity.bindService(
                        Intent(activity, UsbDriverService::class.java),
                        gameStreamHelper.usbDriverServiceConnection, BIND_AUTO_CREATE
                    )
                    info("UsbDriverService bound")
                } catch (e: IllegalArgumentException) {
                    warning("Error binding service connect $e")
                }
                true
            } else {
                false
            }
            onDispose {
                if (bound) {
                    try {
                        activity.unbindService(gameStreamHelper.usbDriverServiceConnection)
                        info("UsbDriverService unbound")
                    } catch (e: IllegalArgumentException) {
                        warning("Error unbinding service connect $e")
                    }
                }
            }
        }

        LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
            val elapsedSeconds = viewModel.getElapsedSessionTimeSeconds()

            if (elapsedSeconds >= 300) {
                if (!isQuitting) {
                    shortcutHelper.addWatchNextShortcut(
                        activity = activity,
                        gameStreamHelper,
                        viewModel,
                        viewModel.computer!!,
                        viewModel.app!!
                    )

                    coroutineScope.launch {
                        PreferenceKeys.putParcelableObject(
                            key = stringPreferencesKey("App"),
                            nvApp = viewModel.app,
                            context = context
                        )
                    }
                }
            } else {
                viewModel.clearSessionTimer()
            }
        }

        LifecycleResumeEffect(key1 = lifecycleOwner) {
            val window = activity.window

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window?.let {
                    val params = it.attributes
                    params.preferMinimalPostProcessing = true
                    it.attributes = params
                }
            }

            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && gameStreamHelper.autoEnterPip) {
                activity.setPictureInPictureParams(
                    gameStreamHelper.getPictureInPictureParams(autoEnter = true)
                )
            }

            onPauseOrDispose {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window?.let {
                        val params = it.attributes
                        params.preferMinimalPostProcessing = false
                        it.attributes = params
                    }
                }
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }


        StarlightTheme {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
            ) {
                AndroidView(
                    factory = { context ->
                        StreamView(context).apply {

                            gameStreamHelper.streamView = this

                            isFocusable = true
                            isFocusableInTouchMode = true
                            defaultFocusHighlightEnabled = false
                            isFocusedByDefault = true

                            setOnGenericMotionListener(gameStreamHelper)
                            setOnTouchListener(gameStreamHelper)
                            setOnKeyListener(gameStreamHelper)
                            setInputCallbacks(gameStreamHelper)
                            setOnCapturedPointerListener { view, event ->
                                gameStreamHelper.handleMotionEvent(view, event)
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                requestUnbufferedDispatch(gameStreamHelper.getUnbufferedSources())
                            }

                            val callback = object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    gameStreamHelper.initializeStreaming()
                                    // Calculate desired frame rate, prioritizing user preference if it's lower or refresh rate reduction is possible.
                                    val desiredFrameRate: Float =
                                        if (gameStreamHelper.mayReduceRefreshRate() || gameStreamHelper.desiredRefreshRate < currentConfig.fps) {
                                            currentConfig.fps.toFloat()
                                        } else {
                                            gameStreamHelper.desiredRefreshRate
                                        }

                                    // Set frame rate based on Android version, handling potential nulls gracefully.
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        holder.surface?.setFrameRate(
                                            desiredFrameRate,
                                            Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                                            Surface.CHANGE_FRAME_RATE_ALWAYS
                                        )
                                    } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                                        holder.surface?.setFrameRate(
                                            desiredFrameRate,
                                            Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                                        )
                                    }

                                    try {
                                        activity.window.setSustainedPerformanceMode(true)
                                    } catch (_: Throwable) {
                                    }

                                    gameStreamHelper.surfaceCreated = true
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) {
                                    require(gameStreamHelper.surfaceCreated) { "Surface changed before creation!" }

                                    if (!gameStreamHelper.attemptedConnection) {
                                        gameStreamHelper.attemptedConnection = true

                                        UiHelper.notifyStreamConnecting(context)

                                        // Null check for decoderRenderer before use, or ensure it's initialized earlier
                                        gameStreamHelper.decoderRenderer.let { renderer ->
                                            renderer?.setRenderTarget(holder)

                                            if (currentConfig.aaudio) {
                                                gameStreamHelper.conn?.start(
                                                    AdaptiveAudioRenderer(
                                                        context,
                                                        currentConfig.enableAudioFx,
                                                        true
                                                    ),
                                                    renderer!!,
                                                    gameStreamHelper
                                                )
                                            } else {
                                                gameStreamHelper.conn?.start(
                                                    AudioRendererFactory.create(
                                                        activity,
                                                        currentConfig
                                                    ),
                                                    renderer!!,
                                                    gameStreamHelper
                                                )
                                            }
                                        }

                                        viewModel.startSessionTimer()
                                    }
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    check(gameStreamHelper.surfaceCreated) { "Surface destroyed before creation!" }
                                    if (gameStreamHelper.attemptedConnection) {
                                        gameStreamHelper.decoderRenderer?.prepareForStop()
                                        if (gameStreamHelper.connected) {
                                            gameStreamHelper.stopConnection()
                                        }
                                    }

                                    try {
                                        activity.window.setSustainedPerformanceMode(false)
                                    } catch (_: Throwable) {
                                    }
                                }
                            }
                            holder.addCallback(callback)
                            tag = callback
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                        .handleGameBackEvent(gameStreamHelper)
                        .onRemoteKey(
                            viewModel = viewModel,
                            perfOverlay = perfOverlay,
                            prefConfig = currentConfig
                        )
                        .focusRequester(streamFocusRequester),
                    onRelease = { view ->
                        (view.tag as? SurfaceHolder.Callback)?.let {
                            view.holder.removeCallback(it)
                        }

                        gameStreamHelper.cleanUp()
                    }
                )

                when {
                    showLoader -> {
                        ProgressDialog(
                            title = stringResource(R.string.conn_establishing_title),
                            message = progressMessage,
                            icon = R.drawable.baseline_private_connectivity_24,
                            onDismissRequest = { viewModel.setProgressDialog(false) })
                    }
                }

                when {
                    showDialog -> {
                        AlertDialog(
                            dialogTitle = title,
                            dialogText = message,
                            confirm = confirmButtonLabel,
                            icon = icon,
                            isNeutralRequired = isNeutralRequired,
                            isCancelRequired = isCancelRequired,
                            onConfirmation = {
                                if (isQuitting) {
                                    viewModel.setDialogValue(false)
                                    viewModel.viewModelScope.launch {
                                        gameStreamHelper.quitGame()
                                    }
                                } else if (error) {
                                    viewModel.getNavController()?.popBackStack()
                                    viewModel.setDialogValue(false)
                                }
                            },
                            onDismissRequest = {
                                viewModel.setDialogValue(false)
                                if (isQuitting) {
                                    viewModel.setIsQuitting(false)
                                }
                            },
                            onNeutralRequest = {
                                viewModel.setDialogValue(false)
                                shortcutHelper.addWatchNextShortcut(
                                    activity = activity,
                                    gameStreamHelper,
                                    viewModel,
                                    viewModel.computer!!,
                                    viewModel.app!!
                                )

                                coroutineScope.launch {
                                    PreferenceKeys.putParcelableObject(
                                        key = stringPreferencesKey("App"),
                                        nvApp = viewModel.app,
                                        context = context
                                    )
                                }
                            },
                            dismiss = cancelButtonLabel,
                            neutral = neutralButtonLabel,
                            dismissOnBackPress = false
                        )
                    }
                }

                when {
                    perfOverlay -> {
                        PerformanceOverlay(
                            text = perfOverlayText,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(16.dp)
                                .preferKeepClear()
                        )
                    }
                }

                when {
                    noticeOverlay -> {
                        NotificationOverlay(
                            text = noticeOverlayText,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                                .preferKeepClear()
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PerformanceOverlay(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier
            .padding(8.dp)
            .wrapContentWidth()
            .wrapContentHeight(),
        colors = SurfaceDefaults.colors(
            Color.Black.copy(alpha = 0.6f)
        ),
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "$text: ",
                color = Color.White,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            )
        }
    }
}

@Composable
fun NotificationOverlay(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier
            .padding(8.dp)
            .wrapContentWidth()
            .wrapContentHeight(),
        colors = SurfaceDefaults.colors(
            Color.Black.copy(alpha = 0.6f)
        ),
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "$text: ",
                color = Color.White,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            )
        }
    }
}