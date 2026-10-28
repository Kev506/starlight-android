package com.starlight.ui.grids

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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
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
import com.starlight.activities.MainView
import com.starlight.computers.ComputerManagerService
import com.starlight.games.GameData
import com.starlight.models.GameViewModel
import com.starlight.models.MainViewModel
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.preferences.PreferenceKeys.dataStoreConfigFlow
import com.starlight.ui.adapters.AppAdapter
import com.starlight.ui.adapters.AppAdapter.Companion.loader
import com.starlight.ui.dialogs.AlertDialog
import com.starlight.ui.helpers.FavoritesHelper
import com.starlight.ui.helpers.ServerHelper
import com.starlight.ui.helpers.UiHelper
import com.starlight.ui.modifiers.handleLeftEvent
import com.starlight.ui.modifiers.handleMainBackEvent
import com.starlight.ui.modifiers.handleMainNavigation
import com.starlight.ui.modifiers.onKeyDown
import com.starlight.utils.AppComputerObject
import com.starlight.utils.Constants.KEY_FAV_SELECTED_POSITION
import com.starlight.utils.Constants.PREF_KEY_LAST_NOTIFIED_CRASH_COUNT
import com.starlight.utils.LimeLog.warning
import com.starlight.utils.Starlight.Companion.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.milliseconds

/**
 * Created by Kevin Andrews on 03/05/2026.
 */

@Composable
fun FavoritesAppGrid(
    viewModel: MainViewModel,
    computer: ComputerDetails?,
    uuid: String
) {

    val activity = LocalActivity.current as? ComponentActivity ?: return

    val gameViewModel = viewModel<GameViewModel> {
        val repository = GameData(
            activity.applicationContext,
            activity
        )
        GameViewModel(repository)
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    val favsScreenHelper = remember {
        FavoritesHelper(
            viewModel = viewModel,
            uiContext = activity,
            name = viewModel.name!!,
            gameViewModel = gameViewModel,
            uuid = uuid,
            pair = viewModel.pairState,
            hidden = viewModel.showHiddenApps
        )
    }

    favsScreenHelper.uiContext = activity
    viewModel.setFavsHelper(favsScreenHelper)

    DisposableEffect(lifecycleOwner) {
        val bound = activity.bindService(
            Intent(
                activity,
                ComputerManagerService::class.java
            ), favsScreenHelper.serviceConnection, Service.BIND_AUTO_CREATE
        )
        onDispose {
            if (bound) {
                try {
                    activity.unbindService(favsScreenHelper.serviceConnection)
                } catch (e: IllegalArgumentException) {
                    warning("Error unbinding service connect $e")
                }
            }
        }
    }

    val items = remember { AppAdapter.itemList }

    FavoritesAppGridContent(
        data = items,
        favsScreenHelper = favsScreenHelper,
        viewModel = viewModel,
        computer = computer,
        uuid = uuid
    )
}

@Composable
fun FavoritesAppGridContent(
    data: List<AppComputerObject>,
    favsScreenHelper: FavoritesHelper,
    viewModel: MainViewModel,
    computer: ComputerDetails?,
    uuid: String
) {
    val focusManager = LocalFocusManager.current
    val composeView = LocalView.current
    val activity = LocalActivity.current as MainView
    val focusRequesters = remember(data.size) { Array(data.size) { FocusRequester() } }
    val isSideMenuOpen by viewModel.isSideMenuOpen.observeAsState(false)
    val selectedApp by viewModel.selectedApp.observeAsState(null)
    val reloaded by viewModel.reloaded.observeAsState(false)
    val isCrash by viewModel.isCrash.observeAsState(false)
    val crashState by viewModel.crashStateFlow.observeAsState(false)
    val crashCount by viewModel.crashCountFlow.observeAsState(0)
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val managerBinder by viewModel.binder.observeAsState(null)
    val startWithQuit by viewModel.startWithQuit.observeAsState(false)
    val isAppRunning by viewModel.isAppRunning.observeAsState(false)
    var focusTrigger by remember { mutableIntStateOf(0) }
    var isGridReady by remember { mutableStateOf(false) }
    val config by activity.dataStoreConfigFlow.collectAsStateWithLifecycle(initialValue = null)

    val gridState = rememberLazyGridState()

    config?.let { currentConfig ->

        LaunchedEffect(data.size, isSideMenuOpen, focusTrigger) {
            if (!isSideMenuOpen && data.isNotEmpty()) {
                if (reloaded) yield() else delay(100.milliseconds)
                val target =
                    if (reloaded) 0 else currentConfig.favSelectedPosition.coerceIn(
                        0,
                        data.lastIndex.coerceAtLeast(0)
                    )

                if (target >= 7) {
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

            favsScreenHelper.startComputerUpdates()
            focusTrigger++

            onPauseOrDispose {
                favsScreenHelper.stopComputerUpdates()
                viewModel.setIsGridMoving(false)
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            colors = SurfaceDefaults.colors(containerColor = Color.Transparent)
        ) {

            val target =
                if (reloaded) 0 else currentConfig.favSelectedPosition.coerceIn(
                    0,
                    data.lastIndex.coerceAtLeast(0)
                )

            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                state = gridState,
                contentPadding = PaddingValues(
                    top = 30.dp,
                    bottom = 30.dp,
                    start = 12.dp,
                    end = 43.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(56.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isGridReady) 1f else 0f)
                    .focusGroup()
                    .focusRestorer(focusRequesters.getOrNull(target) ?: FocusRequester.Default)
            ) {
                itemsIndexed(items = data, key = { index, _ -> index }) { index, item ->

                    val focusRequester = focusRequesters[index]

                    activity.registerForContextMenu(composeView)

                    val imageState = produceState<Any?>(initialValue = null, key1 = item.app) {
                        value = item.app?.let {
                            if (favsScreenHelper.getGameImage(it) != null)
                                favsScreenHelper.getGameImage(it) else loader?.getGameImage(it)
                        }
                    }

                    StandardCardContainer(
                        imageCard = { interactionSource ->
                            Card(
                                modifier = Modifier
                                    .focusRequester(focusRequester)
                                    .handleLeftEvent(targetFocus = focusManager)
                                    .onKeyDown(
                                        viewModel = viewModel,
                                        isSideMenuOpen = isSideMenuOpen
                                    )
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
                                    .padding(start = 1.dp, end = 1.dp, top = 1.dp, bottom = 1.dp)
                                    .padding(1.dp)
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused) {
                                            item.app.let {
                                                viewModel.setSelectedApp(
                                                    AppComputerObject(it)
                                                )
                                            }

                                            viewModel.setBackInProgress(false)
                                        }
                                    }
                                    .aspectRatio(7f / 9f),
                                onLongClick = {
                                    favsScreenHelper.stopComputerUpdates()
                                    composeView.showContextMenu()

                                   coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[intPreferencesKey(KEY_FAV_SELECTED_POSITION)] =
                                                index
                                        }
                                    }
                                },
                                onClick = {
                                    if (favsScreenHelper.lastRunningAppId != 0) {
                                        composeView.showContextMenu()
                                    } else {
                                        favsScreenHelper.showDetails(
                                            app = item,
                                            managerBinder = managerBinder,
                                            computer = computer,
                                            uuid = uuid
                                        )
                                    }

                                   coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[intPreferencesKey(KEY_FAV_SELECTED_POSITION)] =
                                                index
                                        }
                                    }
                                },
                                glow = CardDefaults.glow(
                                    focusedGlow = Glow(
                                        elevation = 24.dp,
                                        elevationColor = Color(0xFF00BCD4)
                                    ),
                                    pressedGlow = Glow(
                                        elevation = 24.dp,
                                        elevationColor = Color(0xFF1B83A9)
                                    )
                                ),
                                shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                                scale = CardDefaults.scale(focusedScale = 1.1f),
                                interactionSource = interactionSource,
                                border = CardDefaults.border(
                                    focusedBorder = Border(
                                        BorderStroke(
                                            2.dp,
                                            Color(0xFF1B83A9)
                                        ),
                                        inset = 2.dp,
                                        shape = RoundedCornerShape(10.dp)
                                    )
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
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(10.dp))
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
                                                onComplete = favsScreenHelper.quitJob(selectedApp!!)
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
                                                onComplete = favsScreenHelper.quitJob(selectedApp!!)
                                            )
                                        }
                                    }
                                }
                            }
                            favsScreenHelper.showDetails(
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
