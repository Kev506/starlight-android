package com.starlight.ui.screens

import android.app.Service
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.StandardCardContainer
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import com.starlight.R
import com.starlight.computers.ComputerManagerService
import com.starlight.models.MainViewModel
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.preferences.PreferenceKeys.dataStoreConfigFlow
import com.starlight.ui.adapters.AppAdapter
import com.starlight.ui.adapters.AppAdapter.Companion.loader
import com.starlight.ui.dialogs.AlertDialog
import com.starlight.ui.helpers.AppViewHelper
import com.starlight.ui.helpers.ServerHelper
import com.starlight.ui.helpers.UiHelper
import com.starlight.ui.modifiers.handleLeftEvent
import com.starlight.ui.modifiers.handleMainBackEvent
import com.starlight.ui.modifiers.handleMainNavigation
import com.starlight.utils.AppComputerObject
import com.starlight.utils.Constants.APP_VIEW_KEY_SELECTED_POSITION
import com.starlight.utils.Constants.PREF_KEY_LAST_NOTIFIED_CRASH_COUNT
import com.starlight.utils.LimeLog.warning
import com.starlight.utils.Starlight.Companion.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.milliseconds

/**
 * Created by Kevin Andrews on 17/04/2026.
 */

@Composable
fun AppViewScreen(
    viewModel: MainViewModel,
    computer: ComputerDetails?,
    uuid: String
) {

    val activity = LocalActivity.current as ComponentActivity
    val lifecycleOwner = LocalLifecycleOwner.current

    val appViewHelper = remember {
        AppViewHelper(
            viewModel = viewModel,
            uiContext = activity,
            uuid = uuid,
            pairState = viewModel.pairState,
            hidden = viewModel.showHiddenApps
        )
    }
    appViewHelper.uiContext = activity
    viewModel.setAppViewHelper(appViewHelper)

    DisposableEffect(lifecycleOwner) {
        val bound = activity.bindService(
            Intent(
                activity,
                ComputerManagerService::class.java
            ), appViewHelper.serviceConnection, Service.BIND_AUTO_CREATE
        )
        onDispose {
            if (bound) {
                try {
                    activity.unbindService(appViewHelper.serviceConnection)
                } catch (e: IllegalArgumentException) {
                    warning("Error unbinding service connect $e")
                }
            }
        }
    }

    val items = remember { AppAdapter.itemList }

    AppViewContent(
        data = items,
        appViewHelper = appViewHelper,
        viewModel = viewModel,
        computer = computer,
        uuid = uuid
    )
}

@Composable
fun AppViewContent(
    data: List<AppComputerObject>,
    appViewHelper: AppViewHelper,
    viewModel: MainViewModel,
    computer: ComputerDetails?,
    uuid: String
) {

    val focusRequesters = remember(data.size) { Array(data.size) { FocusRequester() } }
    val isSideMenuOpen by viewModel.isSideMenuOpen.observeAsState(false)
    val composeView = LocalView.current
    val context = LocalContext.current
    val activity = LocalActivity.current as ComponentActivity
    val focusManager = LocalFocusManager.current
    val isCrash by viewModel.isCrash.observeAsState(false)
    val crashState by viewModel.crashStateFlow.observeAsState(false)
    val crashCount by viewModel.crashCountFlow.observeAsState(0)
    val showDialog by viewModel.openAlertDialog.observeAsState(false)
    val isQuitting by viewModel.isQuitting.observeAsState(false)
    val startWithQuit by viewModel.startWithQuit.observeAsState(false)
    val isCancelRequired by viewModel.isCancelRequired.observeAsState(false)
    val isNeutralRequired by viewModel.isNeutralRequired.observeAsState(false)
    val isAppRunning by viewModel.isAppRunning.observeAsState(false)
    val message by viewModel.dialogMessage.observeAsState("")
    val title by viewModel.dialogTitle.observeAsState("")
    val cancelButtonLabel by viewModel.cancelButtonText.observeAsState("")
    val confirmButtonLabel by viewModel.confirmButtonText.observeAsState("")
    val neutralButtonLabel by viewModel.neutralButtonText.observeAsState("")
    val icon by viewModel.image.observeAsState(R.drawable.baseline_error_outline_24)
    val selectedApp by viewModel.selectedApp.observeAsState(null)
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val reloaded by viewModel.reloaded.observeAsState(false)
    val managerBinder by viewModel.binder.observeAsState(null)
    var isGridReady by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    val config by activity.dataStoreConfigFlow.collectAsStateWithLifecycle(initialValue = null)

    var focusTrigger by remember { mutableIntStateOf(0) }

    config?.let { currentConfig ->

        LaunchedEffect(data.size, isSideMenuOpen, focusTrigger) {
            if (!isSideMenuOpen && data.isNotEmpty()) {
                if (reloaded) yield() else delay(100.milliseconds)
                val target =
                    if (reloaded) 0 else currentConfig.appViewSelectedPosition.coerceIn(
                        0,
                        data.lastIndex.coerceAtLeast(0)
                    )
                val columns = if (currentConfig.smallIconMode) 8 else 7

                if (target >= columns) {
                    gridState.scrollToItem(target, 0)
                    yield()
                }

                focusRequesters.getOrNull(target)?.requestFocus()

                delay(50.milliseconds)
                isGridReady = true

                if (reloaded) viewModel.setReloaded(false)
            }
        }

        LifecycleResumeEffect(key1 = lifecycleOwner) {
            coroutineScope.launch(Dispatchers.IO) {
                UiHelper.reportDecoderCrash(activity, viewModel)
            }

            appViewHelper.startComputerUpdates()
            viewModel.setIsComputerScreen(false)
            focusTrigger++

            onPauseOrDispose {
                appViewHelper.stopComputerUpdates()
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            colors = SurfaceDefaults.colors(containerColor = Color.Black)
        ) {

            val target =
                if (reloaded) 0 else currentConfig.appViewSelectedPosition.coerceIn(
                    0,
                    data.lastIndex.coerceAtLeast(0)
                )

            LazyVerticalGrid(
                columns = if (currentConfig.smallIconMode) GridCells.Fixed(8) else GridCells.Fixed(7),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(62.dp),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isGridReady) 1f else 0f)
                    .focusGroup()
                    .focusRestorer(focusRequesters.getOrNull(target) ?: FocusRequester.Default)
            ) {
                itemsIndexed(items = data, key = { index, _ -> index }) { index, item ->

                    var itemFocused by remember { mutableStateOf(false) }
                    val focusRequester = focusRequesters[index]

                    activity.registerForContextMenu(composeView)

                    val imageState = produceState<Any?>(initialValue = null, key1 = item.app) {
                        value = item.app?.let {
                            if (appViewHelper.getGameImage(it) != null)
                                appViewHelper.getGameImage(it) else loader?.getGameImage(it)
                        }
                    }

                    StandardCardContainer(
                        imageCard = { interactionSource ->
                            Card(
                                modifier = Modifier
                                    .size(95.dp, 128.dp)
                                    .focusRequester(focusRequester)
                                    .handleLeftEvent(targetFocus = focusManager)
                                    .handleMainBackEvent(
                                        viewModel = viewModel,
                                        parent = activity,
                                        isSideMenuOpen = isSideMenuOpen,
                                        scrollState = gridState,
                                        coroutineScope = coroutineScope,
                                        index = index,
                                        focusRequester = focusRequesters
                                    )
                                    .handleMainNavigation(
                                        viewModel = viewModel,
                                        parent = activity,
                                        scrollState = gridState,
                                        coroutineScope = coroutineScope,
                                        index = index,
                                        focusRequester = focusRequesters
                                    )
                                    .onFocusChanged { focusState ->
                                        itemFocused = focusState.isFocused
                                        if (focusState.isFocused) {
                                            item.app.let {
                                                viewModel.setSelectedApp(
                                                    AppComputerObject(it)
                                                )
                                            }
                                        }
                                    },
                                onLongClick = {
                                    appViewHelper.stopComputerUpdates()
                                    composeView.showContextMenu()

                                    coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[intPreferencesKey(
                                                APP_VIEW_KEY_SELECTED_POSITION
                                            )] = index
                                        }
                                    }
                                },
                                onClick = {
                                    if (appViewHelper.lastRunningAppId != 0) {
                                        composeView.showContextMenu()
                                    } else {
                                        appViewHelper.showDetails(
                                            app = item,
                                            managerBinder = managerBinder,
                                            computer = computer,
                                            uuid = uuid
                                        )
                                    }

                                    coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[intPreferencesKey(
                                                APP_VIEW_KEY_SELECTED_POSITION
                                            )] = index
                                        }
                                    }
                                },
                                shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                                scale = CardDefaults.scale(focusedScale = 1.1f),
                                interactionSource = interactionSource,
                                border = CardDefaults.border(
                                    focusedBorder = if (itemFocused) Border(
                                        BorderStroke(3.dp, Color(0xFF1B83A9)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) else Border(BorderStroke(1.dp, Color.Transparent)),
                                )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Transparent),
                                    contentAlignment = Alignment.Center
                                ) {

                                    Text(
                                        modifier = Modifier.wrapContentSize(),
                                        textAlign = TextAlign.Center,
                                        text = if (item.isHidden == true) "" else item.app?.appName
                                            ?: "",
                                        fontSize = 12.sp
                                    )

                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(imageState.value)
                                            .crossfade(false)
                                            .allowHardware(true)
                                            .build(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(95.dp, 128.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .alpha(if (item.isHidden == true) 0.5f else 1f)
                                    )

                                    if (item.isRunning == true) {
                                        Icon(
                                            painter = painterResource(R.drawable.baseline_play_circle_filled_24),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .align(Alignment.TopCenter)
                                                .padding(top = 20.dp)
                                                .size(42.dp)
                                                .zIndex(10f)
                                        )
                                    }
                                }
                            }
                        },
                        title = {}
                    )
                }
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
                            viewModel.setIsQuitting(false)
                            viewModel.setDialogValue(false)
                            coroutineScope.launch {
                                selectedApp!!.app?.let {
                                    computer?.let { gameComputer ->
                                        managerBinder?.let { binder ->
                                            ServerHelper.doQuit(
                                                parent = activity,
                                                computer = gameComputer,
                                                app = it,
                                                binder = binder,
                                                onComplete = appViewHelper.quitJob(selectedApp!!)
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (startWithQuit) {
                            viewModel.setAppRunning(true)
                            viewModel.setStartWithQuit(false)
                            viewModel.setDialogValue(false)
                            ServerHelper.isAppRunning = isAppRunning
                            coroutineScope.launch {
                                selectedApp!!.app?.let {
                                    computer?.let { gameComputer ->
                                        managerBinder?.let { binder ->
                                            ServerHelper.doQuit(
                                                parent = activity,
                                                computer = gameComputer,
                                                app = it,
                                                binder = binder,
                                                onComplete = appViewHelper.quitJob(selectedApp!!)
                                            )
                                        }
                                    }
                                }
                            }
                            appViewHelper.showDetails(
                                app = selectedApp,
                                managerBinder = managerBinder,
                                computer = computer,
                                uuid = computer?.uuid
                            )
                        } else if (isCrash) {
                            viewModel.setIsCrash(false)
                            coroutineScope.launch(Dispatchers.IO) {
                                context.dataStore.edit { preferences ->
                                    preferences[intPreferencesKey(PREF_KEY_LAST_NOTIFIED_CRASH_COUNT)] =
                                        crashCount
                                }
                            }
                            viewModel.updateCrashState(false)
                            viewModel.setDialogValue(false)
                        }
                    },
                    onDismissRequest = {
                        viewModel.setDialogValue(false)
                        if (isQuitting) {
                            viewModel.setIsQuitting(false)
                        } else if (startWithQuit) {
                            viewModel.setStartWithQuit(false)
                            viewModel.setAppRunning(false)
                        } else if (isCrash) {
                            viewModel.setIsCrash(false)
                            viewModel.updateCrashState(false)
                            viewModel.setDialogValue(false)
                        }
                    },
                    onNeutralRequest = {},
                    dismiss = cancelButtonLabel,
                    neutral = neutralButtonLabel,
                    dismissOnBackPress = true
                )
            }
        }
        when {
            crashState -> {
                // Show different dialogs based on crash frequency
                if (crashCount % 3 == 0) {
                    UiHelper.resetStreamingSettings(
                        context
                    )
                    viewModel.setDialogValue(true)
                    viewModel.setDialogImage(R.drawable.baseline_error_outline_24)
                    viewModel.setDialogTitle(
                        stringResource(R.string.title_decoding_reset)
                    )
                    viewModel.setDialogMessage(stringResource(R.string.message_decoding_reset))
                    viewModel.setConfirmButtonLabel(
                        stringResource(android.R.string.ok)
                    )
                    viewModel.setIsCrash(true)
                    viewModel.setIsCancelRequired(false)
                    viewModel.setDismissOnBack(true)
                } else {
                    viewModel.setDialogValue(true)
                    viewModel.setDialogImage(R.drawable.baseline_error_outline_24)
                    viewModel.setDialogTitle(
                        stringResource(R.string.title_decoding_error)
                    )
                    viewModel.setDialogMessage(stringResource(R.string.message_decoding_error))
                    viewModel.setConfirmButtonLabel(
                        stringResource(android.R.string.ok)
                    )
                    viewModel.setIsCrash(true)
                    viewModel.setIsCancelRequired(false)
                    viewModel.setDismissOnBack(true)
                }
            }

            else -> {
                LaunchedEffect(Unit) {
                    viewModel.updateCrashState(false)
                }
            }
        }
    }
}
