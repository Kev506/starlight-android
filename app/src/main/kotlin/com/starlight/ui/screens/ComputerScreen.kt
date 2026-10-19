package com.starlight.ui.screens

import android.app.Service
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.StandardCardContainer
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.Bitmap
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import com.starlight.R
import com.starlight.computers.ComputerManagerService
import com.starlight.models.MainViewModel
import com.starlight.navigation.Screens
import com.starlight.nvstream.jni.StarBridge
import com.starlight.ui.adapters.PcAdapter
import com.starlight.ui.dialogs.AlertDialog
import com.starlight.ui.dialogs.ProgressDialog
import com.starlight.ui.helpers.ComputerHelper
import com.starlight.ui.helpers.UiHelper
import com.starlight.ui.modifiers.handleMainBackEvent
import com.starlight.utils.ComputerObject
import com.starlight.utils.Constants.CONNECTION_TEST_SERVER
import com.starlight.utils.Constants.PREF_KEY_LAST_NOTIFIED_CRASH_COUNT
import com.starlight.utils.Constants.TROUBLESHOOTING_URL
import com.starlight.utils.LimeLog.warning
import com.starlight.utils.Starlight.Companion.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

data class CardItem(
    val posterUri: Bitmap? = null,
    val imageUri: Bitmap? = null,
    val lockImageUri: Bitmap? = null
)

@Composable
fun ComputerScreen(
    viewModel: MainViewModel
) {

    val computerList = remember { PcAdapter.computers }
    var cardItem by remember { mutableStateOf(CardItem()) }
    val activity = LocalActivity.current as ComponentActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val resources = LocalResources.current

    val computer = remember { ComputerHelper(viewModel, activity) }
    computer.uiContext = activity
    viewModel.setComputerHelper(computer)

    val imageUri = remember {
        resources.let {
            ResourcesCompat.getDrawable(
                it,
                R.drawable.ic_computer,
                null
            )
        }
    }
    val offlineUri = remember {
        resources.let {
            ResourcesCompat.getDrawable(
                it,
                R.drawable.ic_pc_offline,
                null
            )
        }
    }
    val unPairedUri = remember {
        resources.let {
            ResourcesCompat.getDrawable(
                it,
                R.drawable.ic_lock,
                null
            )
        }
    }

    LaunchedEffect(Unit) {
        cardItem = cardItem.copy(
            posterUri = imageUri?.toBitmap(),
            imageUri = offlineUri?.toBitmap(),
            lockImageUri = unPairedUri?.toBitmap()
        )
    }

    DisposableEffect(lifecycleOwner) {
        val bound = activity.bindService(
            Intent(
                activity,
                ComputerManagerService::class.java
            ), computer.serviceConnection, Service.BIND_AUTO_CREATE
        )
        onDispose {
            if (bound) {
                try {
                    activity.unbindService(computer.serviceConnection)
                } catch (e: IllegalArgumentException) {
                    warning("Error unbinding service connect $e")
                }
            }
        }
    }

    ComputerScreenContent(
        state = cardItem,
        computer = computer,
        data = computerList,
        viewModel = viewModel
    )
}


@Composable
fun ComputerScreenContent(
    state: CardItem,
    computer: ComputerHelper?,
    data: List<ComputerObject>,
    viewModel: MainViewModel
) {

    val focusRequesters = remember(data.size) { Array(data.size) { FocusRequester() } }
    val noPcFound by viewModel.noPcFoundState.observeAsState(true)
    var focused by remember { mutableStateOf(false) }
    val activity = LocalActivity.current as ComponentActivity
    val doNetTest by viewModel.doNetTest.observeAsState(false)
    val isPairing by viewModel.isPairing.observeAsState(false)
    val isCrash by viewModel.isCrash.observeAsState(false)
    val isDelete by viewModel.isDelete.observeAsState(false)
    val isHelp by viewModel.isHelp.observeAsState(false)
    val showDialog by viewModel.openAlertDialog.observeAsState(false)
    val isCancelRequired by viewModel.isCancelRequired.observeAsState(false)
    val message by viewModel.dialogMessage.observeAsState("")
    val title by viewModel.dialogTitle.observeAsState("")
    val cancelButtonLabel by viewModel.cancelButtonText.observeAsState("")
    val confirmButtonLabel by viewModel.confirmButtonText.observeAsState("")
    val crashState by viewModel.crashStateFlow.observeAsState(false)
    val dismissOnBackPress by viewModel.dismissOnBack.observeAsState(false)
    val showComputerLoader by viewModel.showComputerLoaderState.observeAsState(false)
    val isSideMenuOpen by viewModel.isSideMenuOpen.observeAsState(false)
    val isPaired by viewModel.isPaired.observeAsState(false)
    val isOffline by viewModel.isOffline.observeAsState(false)
    val crashCount by viewModel.crashCountFlow.observeAsState(0)
    val selectedPc by viewModel.selectedPc.observeAsState(null)
    val icon by viewModel.image.observeAsState(R.drawable.baseline_error_outline_24)
    var showLoader by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()
    val composeView = LocalView.current
    val context = LocalContext.current

    val savedStateHandle = viewModel.getNavController()?.currentBackStackEntry?.savedStateHandle

    LifecycleResumeEffect(key1 = lifecycleOwner) {
        val openScreen = savedStateHandle?.get<Boolean>("open_screen") ?: false

        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            UiHelper.reportDecoderCrash(activity, viewModel)
        }

        if (!openScreen) {
            computer?.startComputerUpdates()
        } else {
            savedStateHandle["open_screen"] = false
            viewModel.setComputerLoaderValue(false)
        }

        onPauseOrDispose {
            computer?.stopPolling(false)
        }
    }

    LaunchedEffect(Unit) {
        delay(200.milliseconds)
        if (viewModel.computerChanged) {
            viewModel.computerChanged = false
            viewModel.computer?.let {
                computer?.startDataCollection(
                    it
                )
            }
        }

        if (viewModel.isDatabaseCleared) {
            viewModel.isDatabaseCleared = false
            viewModel.computer?.let {
                computer?.startDataCollection(
                    it
                )
            }
        }
    }

    LaunchedEffect(data.size, isSideMenuOpen) {
        delay(200.milliseconds)
        if (!isSideMenuOpen && data.isNotEmpty()) {
            focusRequesters.getOrNull(0)?.requestFocus()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = Color.Transparent)
    ) {
        LazyHorizontalGrid(
            rows = GridCells.Fixed(1),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(top = 30.dp)
                .focusGroup(),
            state = listState
        ) {

            if (data.isNotEmpty()) {
                items(data.size, key = { it }) { index ->
                    val item = data[index]

                    activity.registerForContextMenu(composeView)

                    StandardCardContainer(
                        imageCard = { interactionSource ->
                            Card(
                                modifier = Modifier
                                    .size(125.dp, 125.dp)
                                    .padding(bottom = 10.dp)
                                    .onFocusChanged { state -> focused = state.isFocused }
                                    .handleMainBackEvent(
                                        viewModel = viewModel,
                                        parent = activity,
                                        isSideMenuOpen = isSideMenuOpen,
                                        scrollState = listState,
                                        coroutineScope = coroutineScope,
                                        index = index,
                                        focusRequester = focusRequesters
                                    )
                                    .focusRequester(focusRequesters[index]),
                                onLongClick = {
                                    item.details?.let { viewModel.setSelectedPc(it) }
                                    composeView.showContextMenu()
                                },
                                onClick = {
                                    when {
                                        isOffline -> {
                                            // Open the menu if a PC is offline or refreshing
                                            item.details?.let { viewModel.setSelectedPc(it) }
                                            composeView.showContextMenu()
                                        }

                                        !isPaired -> {
                                            // Pair an unpaired machine by default
                                            item.details?.let { computer?.doPair(it) }
                                        }

                                        else -> {
                                            // Open app list for paired machine
                                            lifecycleOwner.lifecycleScope.launch {
                                                computer?.doAppList(
                                                    item.details!!,
                                                    newlyPaired = false,
                                                    showHiddenGames = false
                                                )
                                            }
                                        }
                                    }
                                },
                                colors = CardDefaults.colors(
                                    containerColor = Color.Transparent,
                                    contentColor = White,
                                    focusedContentColor = if (focused) Color(0xFF33B5E5) else White,
                                    pressedContentColor = Color(0xC9020122)
                                ),
                                border = CardDefaults.border(
                                    focusedBorder = Border.None,
                                    pressedBorder = Border.None,
                                    border = Border.None
                                ),
                                interactionSource = interactionSource,
                                scale =
                                    CardDefaults.scale(
                                        focusedScale = 1.05f,
                                    )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Transparent),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(state.posterUri)
                                            .crossfade(false)
                                            .allowHardware(true)
                                            .build(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .alpha(
                                                if (isOffline
                                                ) 0.4f else 1f
                                            )
                                    )
                                    if (showComputerLoader) {
                                        CircularProgressIndicator(
                                            color = Color(0xFF33B5E5),
                                            modifier = Modifier
                                                .width(50.dp)
                                                .height(50.dp)
                                                .zIndex(10f)
                                                .padding(10.dp)
                                        )
                                    }
                                    if (isOffline) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(state.imageUri)
                                                .crossfade(false)
                                                .allowHardware(true)
                                                .build(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .width(50.dp)
                                                .height(50.dp)
                                                .padding(10.dp)
                                                .alpha(0.4f)
                                        )
                                    }
                                    if (!isPaired) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(state.lockImageUri)
                                                .crossfade(false)
                                                .allowHardware(true)
                                                .build(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .width(50.dp)
                                                .height(50.dp)
                                                .padding(10.dp)
                                        )
                                    }
                                }
                            }
                        },
                        title = {
                            item.details?.name?.let {
                                Text(
                                    text = it,
                                    fontSize = 18.sp,
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .alpha(
                                            if (isOffline
                                            ) 0.4f else 1f
                                        ),
                                    color = Color(0xFF33B5E5)
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    when {
        showDialog -> {
            AlertDialog(
                onDismissRequest = {
                    if (isHelp) {
                        lifecycleOwner.lifecycleScope.launch {
                            viewModel.url = TROUBLESHOOTING_URL
                            viewModel.getNavController()
                                ?.navigate(Screens.HelpScreen.route)
                        }
                        viewModel.setDialogValue(false)
                        viewModel.setIsHelp(false)
                    } else if (isCrash) {
                        viewModel.setIsCrash(false)
                        viewModel.setDialogValue(false)
                        viewModel.updateCrashState(false)
                    } else if (isPairing) {
                        viewModel.setDialogValue(false)
                        viewModel.setIsPairing(false)
                    } else if (isDelete) {
                        viewModel.setDialogValue(false)
                        viewModel.setIsDelete(false)
                    } else {
                        viewModel.setDialogValue(false)
                    }
                },
                onConfirmation = {
                    if (isDelete) {
                        lifecycleOwner.lifecycleScope.launch {
                            selectedPc?.let { computer?.removeComputer(it) }
                        }
                        viewModel.setDialogValue(false)
                        viewModel.setIsDelete(false)
                    } else if (doNetTest) {
                        viewModel.setDoNetTest(false)
                        viewModel.setDialogValue(false)
                    } else if (isCrash) {
                        viewModel.setIsCrash(false)
                        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            context.dataStore.edit { preferences ->
                                preferences[intPreferencesKey(PREF_KEY_LAST_NOTIFIED_CRASH_COUNT)] = crashCount
                            }
                        }
                        viewModel.updateCrashState(false)
                        viewModel.setDialogValue(false)
                    } else if (isPairing) {
                        viewModel.setIsPairing(false)
                        viewModel.setDialogValue(false)
                    } else {
                        viewModel.setDialogValue(false)
                    }
                },
                dialogTitle = title,
                dialogText = message,
                icon = icon,
                confirm = confirmButtonLabel,
                dismiss = cancelButtonLabel,
                neutral = "",
                isNeutralRequired = false,
                isCancelRequired = isCancelRequired,
                onNeutralRequest = { /* Nothing to do here */ },
                dismissOnBackPress = dismissOnBackPress
            )
        }
    }
    when {
        showLoader -> {
            ProgressDialog(
                title = stringResource(R.string.nettest_title_waiting),
                message = stringResource(R.string.nettest_text_waiting),
                icon = icon,
                onDismissRequest = { showLoader = false }
            )
        }
    }
    when {
        doNetTest -> {

            LaunchedEffect(showLoader) {
                if (showLoader) {
                    delay(3000.milliseconds)
                    showLoader = false
                    viewModel.setDialogValue(true)
                    viewModel.setDismissOnBack(true)
                }
            }

            LaunchedEffect(Unit) {
                showLoader = true

                lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val ret = StarBridge.testClientConnectivity(
                        CONNECTION_TEST_SERVER,
                        443,
                        StarBridge.ML_PORT_FLAG_ALL
                    )
                    withContext(Dispatchers.Main) {
                        viewModel.setDialogMessage(
                            when (ret) {
                                StarBridge.ML_TEST_RESULT_INCONCLUSIVE ->
                                    activity.resources.getString(R.string.nettest_text_inconclusive)

                                0 -> activity.resources.getString(R.string.nettest_text_success)

                                else -> {
                                    activity.resources.getString(R.string.nettest_text_failure) +
                                            StarBridge.stringifyPortFlags(ret, "\n")
                                }
                            }
                        )
                    }
                }
            }

            viewModel.setDialogImage(R.drawable.baseline_network_check_24)
            viewModel.setDialogTitle(
                stringResource(R.string.nettest_title_done)
            )
            viewModel.setConfirmButtonLabel(
                stringResource(android.R.string.ok)
            )
            viewModel.setCancelButtonLabel("")
            viewModel.setIsDelete(false)
            viewModel.setIsHelp(false)
            viewModel.setIsPairing(false)
            viewModel.setIsCrash(false)
            viewModel.setIsCancelRequired(false)
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
                viewModel.setIsDelete(false)
                viewModel.setIsPairing(false)
                viewModel.setIsCrash(true)
                viewModel.setIsHelp(false)
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
                viewModel.setIsDelete(false)
                viewModel.setIsPairing(false)
                viewModel.setIsCrash(true)
                viewModel.setIsHelp(false)
                viewModel.setIsCancelRequired(false)
                viewModel.setDismissOnBack(true)
            }
        }

        else -> {
            LaunchedEffect(Unit) {
                focusRequesters.getOrNull(0)?.requestFocus()
                viewModel.updateCrashState(false)
            }
        }
    }
    when {
        noPcFound -> {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .wrapContentSize()
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF33B5E5),
                        modifier = Modifier.size(50.dp)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = stringResource(id = R.string.searching_pc),
                        style = MaterialTheme.typography.bodyLarge,
                        color = White,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        else -> {
            LaunchedEffect(Unit) {
                focusRequesters.getOrNull(0)?.requestFocus()
            }
        }
    }
}

