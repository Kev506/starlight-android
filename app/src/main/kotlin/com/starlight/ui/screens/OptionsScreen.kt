package com.starlight.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.StandardCardContainer
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.starlight.R
import com.starlight.activities.MainView
import com.starlight.games.GameDataBaseManager
import com.starlight.models.MainViewModel
import com.starlight.navigation.Screens
import com.starlight.ui.dialogs.AlertDialog
import com.starlight.ui.helpers.CacheHelper
import com.starlight.ui.helpers.OptionsHelper
import com.starlight.ui.modifiers.handleLeftEvent
import com.starlight.ui.modifiers.handleWrapEvent
import com.starlight.utils.Constants.DATABASE_PREF
import com.starlight.utils.Constants.ENABLE_HDR_PREF_STRING
import com.starlight.utils.Constants.SETUP_GUIDE_URL
import com.starlight.utils.Starlight.Companion.dataStore
import com.starlight.utils.Starlight.Companion.getDeviceName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds


@Composable
fun OptionsScreen(
    viewModel: MainViewModel
) {
    val activity = LocalActivity.current as ComponentActivity
    val optionsHelper = remember { OptionsHelper(viewModel, activity) }

    OptionsScreenContent(
        data = viewModel.items,
        options = optionsHelper,
        viewModel = viewModel
    )
}

@Composable
fun OptionsScreenContent(
    data: List<OptionsHelper.TvItem>,
    options: OptionsHelper,
    viewModel: MainViewModel
) {

    val openAlertDialog by viewModel.openAlertDialog.observeAsState(false)
    var focusedIndex by rememberSaveable { mutableIntStateOf(0) }
    val dataSize = data.size
    val focusRequesters = remember(dataSize) { Array(dataSize) { FocusRequester() } }

    val message by viewModel.dialogMessage.observeAsState("")
    val title by viewModel.dialogTitle.observeAsState("")
    val cancelButtonLabel by viewModel.cancelButtonText.observeAsState("")
    val confirmButtonLabel by viewModel.confirmButtonText.observeAsState("")
    val icon by viewModel.image.observeAsState(null)
    val isCancelRequired by viewModel.isCancelRequired.observeAsState(false)
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val resources = LocalResources.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val activity = LocalActivity.current as? ComponentActivity
    var focused by remember { mutableStateOf(false) }
    val state = rememberLazyGridState()

    val isCacheSelected = remember { mutableStateOf(false) }
    val isDatabaseSelected = remember { mutableStateOf(false) }
    val isCleanSweep = remember { mutableStateOf(false) }

    val isGoogleTV = remember {
        getDeviceName() == "Google Chromecast"
    }

    val isGoogleTVStreamer = remember {
        getDeviceName() == "Google TV Streamer"
    }

    val focusScale by animateFloatAsState(
        targetValue = if (focused) 1.12f else 1.1f
    )

    LaunchedEffect(data.size) {
        if (data.isNotEmpty()) {
            delay(200.milliseconds)
            focusRequesters.getOrNull(focusedIndex)?.requestFocus()
        }
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            viewModel.setOptionsScreenState(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        LazyHorizontalGrid(
            rows = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            state = state,
            modifier = Modifier
                .padding(top = 30.dp)
                .height(300.dp)
                .width(600.dp)
                .focusGroup()
        ) {

            if (dataSize > 0) {
                itemsIndexed(items = data, key = { index, _ -> index }) { index, item ->
                    val focusRequester = if (index < focusRequesters.size) {
                        focusRequesters[index]
                    } else {
                        FocusRequester.Default
                    }

                    StandardCardContainer(
                        imageCard = { interactionSource ->
                            Card(
                                modifier = Modifier
                                    .size(125.dp, 140.dp)
                                    .aspectRatio(1.1f)
                                    .focusRequester(focusRequester = focusRequester)
                                    .handleWrapEvent(
                                        index = index,
                                        data = data,
                                        focusRequesters = focusRequesters,
                                        scrollState = state,
                                        coroutineScope = coroutineScope,
                                        rowCount = 2
                                    )
                                    .handleLeftEvent(targetFocus = focusManager)
                                    .onFocusChanged {
                                        focused = it.isFocused
                                        if (it.isFocused) {
                                            focusedIndex = index
                                            viewModel.setBackInProgress(false)
                                        }
                                    },
                                onClick = {
                                    when (item.imageResourceId) {
                                        R.drawable.ic_settings -> {
                                            viewModel.getNavController()
                                                ?.navigate(Screens.MainSettingsScreen.route) {
                                                    launchSingleTop = true
                                                }
                                        }

                                        R.drawable.ic_help -> {

                                            // Save the current route, we need to avoid the focus trap on back
                                            val currentRoute =
                                                viewModel.getNavController()?.currentDestination?.route
                                            if (currentRoute != null) {
                                                viewModel.setRoute(currentRoute)
                                            }

                                            viewModel.url = SETUP_GUIDE_URL
                                            viewModel.getNavController()
                                                ?.navigate(Screens.HelpScreen.route) {
                                                    launchSingleTop = true
                                                }
                                        }

                                        R.drawable.baseline_exit_to_app_24 -> {
                                            viewModel.getNavController()
                                                ?.navigate(Screens.Exit.route)
                                        }

                                        R.drawable.ic_baseline_hdr_off_24 -> {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                context.dataStore.edit { preferences ->
                                                    preferences[booleanPreferencesKey(
                                                        ENABLE_HDR_PREF_STRING
                                                    )] = true
                                                }

                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(
                                                        activity,
                                                        activity?.getString(R.string.hdr_on),
                                                        Toast.LENGTH_SHORT
                                                    ).show()

                                                    options.loadDataAsync()
                                                }
                                            }
                                        }

                                        R.drawable.ic_baseline_hdr_on_24 -> {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                context.dataStore.edit { preferences ->
                                                    preferences[booleanPreferencesKey(
                                                        ENABLE_HDR_PREF_STRING
                                                    )] = false
                                                }

                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(
                                                        activity,
                                                        activity?.getString(R.string.hdr_off),
                                                        Toast.LENGTH_SHORT
                                                    ).show()

                                                    options.loadDataAsync()
                                                }
                                            }
                                        }

                                        R.drawable.baseline_delete_sweep_24 -> {
                                            viewModel.setDialogImage(R.drawable.baseline_delete_sweep_24)
                                            viewModel.setDialogTitle(resources.getString(R.string.clear_favorites))
                                            viewModel.setDialogMessage(
                                                if (isGoogleTV or isGoogleTVStreamer) {
                                                    resources.getString(R.string.are_you_sure_you_want_to_clear_all_favorites)
                                                } else {
                                                    resources.getString(R.string.clear_favs_msg)
                                                }
                                            )
                                            viewModel.setConfirmButtonLabel(resources.getString(R.string.yes))
                                            viewModel.setCancelButtonLabel(resources.getString(R.string.cancel))
                                            viewModel.setIsCancelRequired(true)
                                            viewModel.setDialogValue(true)
                                            isCleanSweep.value = true
                                        }

                                        R.drawable.baseline_cached_24 -> {
                                            viewModel.setDialogImage(R.drawable.baseline_cached_24)
                                            viewModel.setDialogTitle(resources.getString(R.string.clear_cache))
                                            viewModel.setDialogMessage(resources.getString(R.string.restart_now))
                                            viewModel.setConfirmButtonLabel(resources.getString(R.string.yes))
                                            viewModel.setCancelButtonLabel(resources.getString(R.string.cancel))
                                            viewModel.setIsCancelRequired(true)
                                            viewModel.setDialogValue(true)
                                            isCacheSelected.value = true
                                        }

                                        R.drawable.baseline_update_24 -> {
                                            viewModel.setDialogImage(R.drawable.baseline_update_24)
                                            viewModel.setDialogTitle(resources.getString(R.string.update_database))
                                            viewModel.setDialogMessage(resources.getString(R.string.update_the_database))
                                            viewModel.setConfirmButtonLabel(resources.getString(R.string.yes))
                                            viewModel.setCancelButtonLabel(resources.getString(R.string.cancel))
                                            viewModel.setIsCancelRequired(true)
                                            viewModel.setDialogValue(true)
                                            isDatabaseSelected.value = true
                                        }
                                    }
                                },
                                colors = CardDefaults.colors(
                                    containerColor = Color(0xFF026D91),
                                    contentColor = White,
                                    pressedContentColor = White,
                                    focusedContainerColor = Color(0xFF1B83A9),
                                    pressedContainerColor = Color(0xC9020122)
                                ),
                                border = CardDefaults.border(
                                    focusedBorder = Border.None,
                                    pressedBorder = Border.None,
                                    border = Border.None
                                ),
                                interactionSource = interactionSource,
                                scale =
                                    CardDefaults.scale(
                                        focusedScale = focusScale,
                                        pressedScale = 1.0f
                                    ),
                                shape = CardDefaults.shape(
                                    RoundedCornerShape(8.dp)
                                )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Transparent),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .wrapContentSize()
                                            .padding(horizontal = 16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(item.imageResourceId)
                                                .crossfade(false)
                                                .build(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(36.dp)
                                        )

                                        Spacer(modifier = Modifier.height(4.dp))

                                        Text(
                                            text = item.title,
                                            fontSize = 14.sp,
                                            color = White,
                                            textAlign = TextAlign.Center
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
    }
    when {
        openAlertDialog -> {
            message.let {
                icon?.let { icon ->
                    AlertDialog(
                        onDismissRequest = {
                            viewModel.setDialogValue(false)
                        },
                        onConfirmation = {
                            if (isCacheSelected.value) {
                                coroutineScope.launch {
                                    CacheHelper.clearAppCache(context)
                                    restartApplication(context)
                                }
                                isCacheSelected.value = false
                                viewModel.setDialogValue(false)
                            } else if (isDatabaseSelected.value) {
                                coroutineScope.launch {
                                    val dbManager =
                                        GameDataBaseManager.getInstance(context)
                                    dbManager?.deleteGameData(
                                        context,
                                        dbManager.writableDatabase
                                    )

                                    coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[booleanPreferencesKey(DATABASE_PREF)] =
                                                false
                                        }
                                    }

                                    coroutineScope.launch {
                                        viewModel.isDatabaseCleared = true
                                        val intent =
                                            Intent(activity, MainView::class.java).apply {
                                                val bundle = Bundle()
                                                bundle.putBoolean(
                                                    "DATABASE_CLEARED",
                                                    viewModel.isDatabaseCleared
                                                )
                                                putExtra("appBundle", bundle)
                                            }.addFlags(
                                                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                                            )
                                        activity?.startActivity(intent)

                                        activity?.finish()
                                    }
                                }
                                viewModel.setDialogValue(false)
                                isDatabaseSelected.value = false
                            } else if (isCleanSweep.value) {
                                options.handleClearFavorites()
                                isCleanSweep.value = false
                                viewModel.setDialogValue(false)
                            } else {
                                viewModel.setDialogValue(false)
                            }

                        },
                        dialogTitle = title,
                        dialogText = it,
                        icon = icon,
                        confirm = confirmButtonLabel,
                        dismiss = cancelButtonLabel,
                        neutral = "",
                        isNeutralRequired = false,
                        isCancelRequired = isCancelRequired,
                        onNeutralRequest = { /* Nothing to do here */ },
                        dismissOnBackPress = true
                    )
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

