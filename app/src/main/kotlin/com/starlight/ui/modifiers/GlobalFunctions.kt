package com.starlight.ui.modifiers

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.starlight.activities.MainView
import com.starlight.models.MainViewModel
import com.starlight.preferences.DataStoreConfiguration
import com.starlight.preferences.PreferenceKeys.fromPreferences
import com.starlight.ui.helpers.GameScreenHelper
import com.starlight.ui.helpers.OptionsHelper
import com.starlight.utils.Constants.APP_VIEW_KEY_SELECTED_POSITION
import com.starlight.utils.Constants.KEY_FAV_SELECTED_POSITION
import com.starlight.utils.Constants.KEY_SELECTED_POSITION
import com.starlight.utils.Constants.LAST_KEY_DOWN_TIME
import com.starlight.utils.Constants.VIDEO_SELECTED_POSITION
import com.starlight.utils.LimeLog
import com.starlight.utils.Starlight.Companion.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Created by Kevin Andrews on 11/04/2026.
 */


// Composable to display a loading overlay when isLoading is true
@Composable
fun LoadingOverlay(
    isLoading: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f)
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator(
                    strokeCap = StrokeCap.Round,
                    color = Color(0xFF33B5E5),
                    modifier = Modifier
                        .size(50.dp)
                        .zIndex(10f)
                )
            }
        }
    }
}

// Extension function to handle D-Pad key events
fun Modifier.handleKeyEvent(
    viewModel: MainViewModel,
    parent: ComponentActivity,
    isSideMenuOpen: Boolean
): Modifier = this.onKeyEvent { event ->

    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
        val screenState = viewModel.getScreenState()
        when (event.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                when (parent) {
                    is MainView -> {
                        when (screenState) {
                            "MainScreen" -> {
                                parent.handleKeyLeft(
                                    isSideMenuOpen
                                )
                            }
                            "FavoritesScreen" -> {
                                parent.handleKeyLeft(
                                    isSideMenuOpen
                                )
                            }

                            "ComputerScreen" -> {
                                parent.handleKeyLeft(
                                    isSideMenuOpen
                                )
                            }
                        }
                    }
                }
                true
            }

            KeyEvent.KEYCODE_BACK -> {
                when (parent) {
                    is MainView -> {
                        when (screenState) {
                            "HelpScreen" -> {
                                parent.handleHelpBackPress()
                            }

                            "SettingsScreen" -> {
                                parent.handleOptionsBackPress()
                            }

                            "MainSettingsScreen" -> {
                                parent.handleMainSettingsBackPress()
                            }

                            "DetailsScreen" -> {
                                parent.handleDetailsBackPress()
                            }

                            "TrailerScreen" -> {
                                parent.handleTrailerBackPress()
                            }

                            "DataCollectionScreen" -> {
                                parent.handleDataCollectionBackPress()
                            }

                            "AddComputerScreen" -> {
                                parent.handleAddComputerBackPress()
                            }
                        }
                    }
                }
                true
            }

            else -> {
                false
            }
        }
    } else false
}

// Extension function to handle scroll events
fun Modifier.handleScrollEvent(
    scrollState: ScrollState,
    scrollAmount: Float
): Modifier = this.composed {

    val coroutineScope = rememberCoroutineScope()

    var scrollJob: Job? = null

    this.onKeyEvent { event ->
        event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && when (event.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                scrollJob?.cancel()
                scrollJob = coroutineScope.launch {
                    scrollState.animateScrollBy(scrollAmount)
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                scrollJob?.cancel()
                scrollJob = coroutineScope.launch {
                    scrollState.animateScrollBy(-scrollAmount)
                }
                true
            }

            else -> false
        }
    }
}

// Extension function to handle D-Pad left key events
fun Modifier.handleLeftEvent(
    targetFocus: FocusManager,
): Modifier = this.onKeyEvent { event ->
    if (event.key == Key.DirectionLeft && event.type == KeyEventType.KeyDown) {
        targetFocus.moveFocus(FocusDirection.Left)
        true
    } else {
        false
    }
}

fun Modifier.handleWrapEvent(
    index: Int,
    data: List<OptionsHelper.TvItem>,
    focusRequesters: Array<FocusRequester>,
    scrollState: LazyGridState,
    coroutineScope: CoroutineScope,
    rowCount: Int
): Modifier = this.onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown) {
        val totalItems = data.size
        if (totalItems == 0) return@onPreviewKeyEvent false

        val currentRow = index % rowCount

        when (event.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (index != 0) {
                    coroutineScope.launch {
                        scrollState.animateScrollToItem(0)
                        focusRequesters.getOrNull(0)?.requestFocus()
                    }
                    return@onPreviewKeyEvent true
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val nextIndexInRow = index + rowCount
                if (nextIndexInRow >= totalItems) {
                    // Last column in the current row, wrap to the start of the next row
                    val nextRow = currentRow + 1
                    val targetIndex = if (nextRow < rowCount && nextRow < totalItems) nextRow else 0

                    coroutineScope.launch {
                        scrollState.animateScrollToItem(0)
                        focusRequesters.getOrNull(targetIndex)?.requestFocus()
                    }
                    return@onPreviewKeyEvent true
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (index < rowCount) {
                    // First column in the current row, wrap to the end of the previous row
                    val prevRow = if (currentRow > 0) currentRow - 1 else rowCount - 1

                    var targetIndex = data.lastIndex
                    while (targetIndex >= 0 && targetIndex % rowCount != prevRow) {
                        targetIndex--
                    }

                    if (targetIndex >= 0) {
                        coroutineScope.launch {
                            scrollState.animateScrollToItem(targetIndex)
                            focusRequesters.getOrNull(targetIndex)?.requestFocus()
                        }
                        return@onPreviewKeyEvent true
                    }
                }
            }
        }
    }
    false
}

// Extension function to handle MainView D-Pad back events
fun Modifier.handleMainBackEvent(
    viewModel: MainViewModel,
    parent: ComponentActivity,
    isSideMenuOpen: Boolean,
    scrollState: LazyGridState,
    coroutineScope: CoroutineScope,
    index: Int,
    focusRequester: Array<FocusRequester>
): Modifier = this.onKeyEvent { event ->
    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {

        val screenState = viewModel.getScreenState()
        when (event.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                when (parent) {
                    is MainView -> {
                        when (screenState) {
                            "MainScreen" -> {
                                when {
                                    index != 0 -> {
                                        coroutineScope.launch {
                                            scrollState.scrollToItem(0)

                                            focusRequester[0].requestFocus()

                                            coroutineScope.launch(Dispatchers.IO) {
                                                parent.dataStore.edit { preferences ->
                                                    preferences[intPreferencesKey(KEY_SELECTED_POSITION)] = 0
                                                }
                                            }
                                        }
                                        return@onKeyEvent true
                                    }

                                    !isSideMenuOpen -> {
                                        viewModel.setIsSideMenuOpen(true)
                                        viewModel.setItemSelected(0)

                                        return@onKeyEvent true
                                    }
                                }
                            }
                            "FavoritesScreen" -> {
                                when {
                                    index != 0 -> {
                                        coroutineScope.launch {
                                            scrollState.scrollToItem(0)

                                            focusRequester[0].requestFocus()

                                            coroutineScope.launch(Dispatchers.IO) {
                                                parent.dataStore.edit { preferences ->
                                                    preferences[intPreferencesKey(KEY_FAV_SELECTED_POSITION)] = 0
                                                }
                                            }
                                        }
                                        return@onKeyEvent true
                                    }

                                    !isSideMenuOpen -> {
                                        viewModel.setIsSideMenuOpen(true)
                                        viewModel.setItemSelected(1)

                                        return@onKeyEvent true
                                    }
                                }
                            }
                            "AppViewScreen" -> {
                                when {
                                    index != 0 -> {
                                        coroutineScope.launch {
                                            scrollState.scrollToItem(0)

                                            focusRequester[0].requestFocus()

                                            coroutineScope.launch(Dispatchers.IO) {
                                                parent.dataStore.edit { preferences ->
                                                    preferences[intPreferencesKey(APP_VIEW_KEY_SELECTED_POSITION)] = 0
                                                }
                                            }
                                        }
                                        return@onKeyEvent true
                                    }

                                    !isSideMenuOpen -> {
                                        viewModel.setIsSideMenuOpen(true)
                                        viewModel.setItemSelected(0)

                                        return@onKeyEvent true
                                    }
                                }
                            }
                            "VideoScreen" -> {
                                when {
                                    index != 0 -> {
                                        coroutineScope.launch {
                                            scrollState.scrollToItem(0)

                                            focusRequester[0].requestFocus()

                                            coroutineScope.launch(Dispatchers.IO) {
                                                parent.dataStore.edit { preferences ->
                                                    preferences[intPreferencesKey(VIDEO_SELECTED_POSITION)] = 0
                                                }
                                            }
                                        }
                                        return@onKeyEvent true
                                    }

                                    else -> {
                                        viewModel.getNavController()?.popBackStack()
                                        viewModel.setIsSideMenuOpen(false)
                                        viewModel.videos.clear()

                                        if (!viewModel.isFavs) {
                                            viewModel.setScreenState("MainScreen")
                                            viewModel.setItemSelected(0)
                                        } else {
                                            viewModel.setScreenState("FavoritesScreen")
                                            viewModel.setItemSelected(1)
                                        }
                                        return@onKeyEvent true
                                    }
                                }
                            }

                            "ComputerScreen" -> {
                                when {
                                    index != 0 -> {
                                        coroutineScope.launch {
                                            scrollState.scrollToItem(0)
                                            focusRequester[0].requestFocus()
                                        }
                                        return@onKeyEvent true
                                    }

                                    !isSideMenuOpen -> {
                                        viewModel.setIsSideMenuOpen(true)
                                        viewModel.setItemSelected(0)

                                        return@onKeyEvent true
                                    }
                                }
                            }
                        }
                    }
                }
                true
            }

            else -> {
                false
            }
        }
    } else false
}

// Extension function to handle Menu D-Pad back events
fun Modifier.handleMainMenuBackEvent(
    viewModel: MainViewModel,
    parent: ComponentActivity,
    isSideMenuOpen: Boolean,
    coroutineScope: CoroutineScope
): Modifier = this.onKeyEvent { event ->
    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {

        val screenState = viewModel.getScreenState()

        when (event.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                when (parent) {
                    is MainView -> {
                        when (screenState) {
                            "FavoritesScreen" -> {
                                when {
                                    isSideMenuOpen -> {
                                        viewModel.getNavController()!!.popBackStack()
                                        viewModel.setItemSelected(0)

                                        viewModel.isFavs = false
                                        viewModel.setIsSideMenuOpen(false)

                                        coroutineScope.launch(Dispatchers.IO) {
                                            parent.dataStore.edit { preferences ->
                                                preferences[intPreferencesKey(KEY_FAV_SELECTED_POSITION)] = 0
                                            }
                                        }
                                    }
                                }
                            }
                            "MainScreen" -> {
                                when {
                                    isSideMenuOpen -> {

                                        coroutineScope.launch(Dispatchers.IO) {
                                            parent.dataStore.edit { preferences ->
                                                preferences[intPreferencesKey(KEY_SELECTED_POSITION)] = 0
                                            }
                                        }

                                        if (viewModel.getLanguageChanged()) {
                                            viewModel.setLanguageChanged(false)
                                            viewModel.getNavController()!!.popBackStack()
                                            viewModel.setScreenState("ComputerScreen")
                                            viewModel.setIsMainView(false)
                                            viewModel.setBackInProgress(false)
                                            viewModel.setItemSelected(0)
                                        } else {
                                            viewModel.getNavController()!!.popBackStack()
                                            viewModel.setScreenState("ComputerScreen")
                                            viewModel.setIsMainView(false)
                                            viewModel.setBackInProgress(false)
                                            viewModel.setItemSelected(0)
                                        }
                                    }
                                }
                            }
                            "AppViewScreen" -> {
                                when {
                                    isSideMenuOpen -> {

                                        coroutineScope.launch(Dispatchers.IO) {
                                            parent.dataStore.edit { preferences ->
                                                preferences[intPreferencesKey(APP_VIEW_KEY_SELECTED_POSITION)] = 0
                                            }
                                        }

                                        if (viewModel.getLanguageChanged()) {
                                            viewModel.setLanguageChanged(false)
                                            viewModel.getNavController()!!.popBackStack()
                                            viewModel.setScreenState("ComputerScreen")
                                            viewModel.setIsMainView(false)
                                            viewModel.setBackInProgress(false)
                                            viewModel.setItemSelected(0)
                                        } else {
                                            viewModel.getNavController()!!.popBackStack()
                                            viewModel.setScreenState("ComputerScreen")
                                            viewModel.setIsMainView(false)
                                            viewModel.setBackInProgress(false)
                                            viewModel.setItemSelected(0)
                                        }
                                    }
                                }
                            }

                            "ComputerScreen" -> {
                                when {
                                    isSideMenuOpen -> {
                                        parent.finish()
                                    }
                                }
                            }
                        }
                    }
                }
                true
            }

            else -> {
                false
            }
        }
    } else false
}

// Extension function to handle MainView D-Pad navigation events
fun Modifier.handleMainNavigation(
    viewModel: MainViewModel,
    parent: ComponentActivity,
    scrollState: LazyGridState,
    coroutineScope: CoroutineScope,
    index: Int,
    focusRequester: Array<FocusRequester>
): Modifier = this.onPreviewKeyEvent { event ->

    val config = fromPreferences(parent)
    val screenState = viewModel.getScreenState()
    val gridCells = if (config.smallIconMode) 8 else 7
    val mainCells = 7
    val videoCells = 4

    if (event.type == KeyEventType.KeyDown) {
        val isFirstInRow = index % gridCells == 0
        val isLastInRow = (index % gridCells == gridCells - 1) ||
                (index == scrollState.layoutInfo.totalItemsCount - 1)

        val videoFirstInRow = index % videoCells == 0
        val videoLastInRow = (index % videoCells == videoCells - 1) ||
                (index == scrollState.layoutInfo.totalItemsCount - 1)

        val mainFirstInRow = index % mainCells == 0
        val mainLastInRow = (index % mainCells == mainCells - 1) ||
                (index == scrollState.layoutInfo.totalItemsCount - 1)

        when (event.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                when (parent) {
                    is MainView -> {
                        when (screenState) {
                            "MainScreen" -> {
                                if (mainFirstInRow) {
                                    if (index == 0) {
                                        viewModel.setIsSideMenuOpen(true)
                                        return@onPreviewKeyEvent true
                                    } else {
                                        coroutineScope.launch {
                                            val target = index - 1
                                            scrollState.animateScrollToItem(target)
                                            focusRequester[target].requestFocus()
                                        }
                                        return@onPreviewKeyEvent true
                                    }
                                }
                            }
                            "FavoritesScreen" -> {
                                if (mainFirstInRow) {
                                    if (index == 0) {
                                        viewModel.setIsSideMenuOpen(true)
                                        return@onPreviewKeyEvent true
                                    } else {
                                        coroutineScope.launch {
                                            val target = index - 1
                                            scrollState.animateScrollToItem(target)
                                            focusRequester[target].requestFocus()
                                        }
                                        return@onPreviewKeyEvent true
                                    }
                                }
                            }
                            "AppViewScreen" -> {
                                if (isFirstInRow) {
                                    if (index == 0) {
                                        viewModel.setIsSideMenuOpen(true)
                                        return@onPreviewKeyEvent true
                                    } else {
                                        coroutineScope.launch {
                                            val target = index - 1
                                            scrollState.animateScrollToItem(target)
                                            focusRequester[target].requestFocus()
                                        }
                                        return@onPreviewKeyEvent true
                                    }
                                }
                            }
                            "VideoScreen" -> {
                                if (videoFirstInRow) {
                                    if (index == 0) {
                                        viewModel.setIsSideMenuOpen(true)
                                        return@onPreviewKeyEvent true
                                    } else {
                                        coroutineScope.launch {
                                            val target = index - 1
                                            scrollState.animateScrollToItem(target)
                                            focusRequester[target].requestFocus()
                                        }
                                        return@onPreviewKeyEvent true
                                    }
                                }
                            }
                        }
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when (parent) {
                    is MainView -> {
                        when (screenState) {
                            "MainScreen" -> {
                                if (mainLastInRow) {
                                    if (index < scrollState.layoutInfo.totalItemsCount - 1) {
                                        coroutineScope.launch {
                                            val target = index + 1
                                            scrollState.animateScrollToItem(target)
                                            focusRequester[target].requestFocus()
                                        }
                                        return@onPreviewKeyEvent true
                                    } else {
                                        coroutineScope.launch {
                                            scrollState.animateScrollToItem(0)
                                            focusRequester[0].requestFocus()
                                        }
                                        return@onPreviewKeyEvent true
                                    }
                                }
                            }
                            "FavoritesScreen" -> {
                                if (mainLastInRow) {
                                    if (index < scrollState.layoutInfo.totalItemsCount - 1) {
                                        coroutineScope.launch {
                                            val target = index + 1
                                            scrollState.animateScrollToItem(target)
                                            focusRequester[target].requestFocus()
                                        }
                                        return@onPreviewKeyEvent true
                                    } else {
                                        coroutineScope.launch {
                                            scrollState.animateScrollToItem(0)
                                            focusRequester[0].requestFocus()
                                        }
                                        return@onPreviewKeyEvent true
                                    }
                                }
                            }
                            "AppViewScreen" -> {
                                if (isLastInRow) {
                                    if (index < scrollState.layoutInfo.totalItemsCount - 1) {
                                        coroutineScope.launch {
                                            val target = index + 1
                                            scrollState.animateScrollToItem(target)
                                            focusRequester[target].requestFocus()
                                        }
                                        return@onPreviewKeyEvent true
                                    } else {
                                        coroutineScope.launch {
                                            scrollState.animateScrollToItem(0)
                                            focusRequester[0].requestFocus()
                                        }
                                        return@onPreviewKeyEvent true
                                    }
                                }
                            }
                            "VideoScreen" -> {
                                if (videoLastInRow) {
                                    if (index < scrollState.layoutInfo.totalItemsCount - 1) {
                                        coroutineScope.launch {
                                            val target = index + 1
                                            scrollState.animateScrollToItem(target)
                                            focusRequester[target].requestFocus()
                                        }
                                        return@onPreviewKeyEvent true
                                    } else {
                                        coroutineScope.launch {
                                            scrollState.animateScrollToItem(0)
                                            focusRequester[0].requestFocus()
                                        }
                                        return@onPreviewKeyEvent true
                                    }
                                }
                            }
                        }
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                when (parent) {
                    is MainView -> {
                        when (screenState) {
                            "MainScreen" -> {
                                if (index == scrollState.layoutInfo.totalItemsCount - 1) {
                                    coroutineScope.launch {
                                        scrollState.animateScrollToItem(0)
                                        focusRequester[0].requestFocus()
                                    }
                                    return@onPreviewKeyEvent true
                                }
                            }
                            "FavoritesScreen" -> {
                                if (index == scrollState.layoutInfo.totalItemsCount - 1) {
                                    coroutineScope.launch {
                                        scrollState.animateScrollToItem(0)
                                        focusRequester[0].requestFocus()
                                    }
                                    return@onPreviewKeyEvent true
                                }
                            }
                            "AppViewScreen" -> {
                                if (index == scrollState.layoutInfo.totalItemsCount - 1) {
                                    coroutineScope.launch {
                                        scrollState.animateScrollToItem(0)
                                        focusRequester[0].requestFocus()
                                    }
                                    return@onPreviewKeyEvent true
                                }
                            }
                            "VideoScreen" -> {
                                if (index == scrollState.layoutInfo.totalItemsCount - 1) {
                                    coroutineScope.launch {
                                        scrollState.animateScrollToItem(0)
                                        focusRequester[0].requestFocus()
                                    }
                                    return@onPreviewKeyEvent true
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    false
}

// Extension function to handle D-Pad right
fun Modifier.handleOnKeyRight(
    viewModel: MainViewModel,
    parent: ComponentActivity,
    mainContentRequester: FocusRequester
): Modifier = this.onKeyEvent { event ->
    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
        when (event.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when (parent) {
                    is MainView -> {
                        viewModel.setIsSideMenuOpen(false)
                        mainContentRequester.requestFocus()
                        return@onKeyEvent true
                    }
                }
            }
        }
    }
    false
}

// Extension function to handle Game back event
fun Modifier.handleGameBackEvent(
    parent: GameScreenHelper
): Modifier = this.onKeyEvent { event ->
    when (event.type) {
        KeyEventType.KeyDown -> {
            when (event.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    parent.handleExitConfirmationCallback()
                    return@onKeyEvent true
                }
            }
        }
    }
    false
}

// Extension function to handle remote key event for stats overlay
fun Modifier.onRemoteKey(
    viewModel: MainViewModel,
    perfOverlay: Boolean,
    prefConfig: DataStoreConfiguration
): Modifier = this.onPreviewKeyEvent { event ->

    if (prefConfig.enablePerfOverlay) {
        return@onPreviewKeyEvent false
    }

    val isStatsKey = when (event.nativeKeyEvent.keyCode) {
        KeyEvent.KEYCODE_PROG_BLUE -> true
        else -> false
    }

    if (isStatsKey) {
        if (event.type == KeyEventType.KeyDown) {
            val targetState = !perfOverlay
            viewModel.setPerfOverlay(targetState)
            viewModel.setStatsRequired(targetState)
        }
        return@onPreviewKeyEvent true
    }

    false
}

// Extension function to throttle D-Pad scroll speed on key down
fun Modifier.onKeyDown(
    viewModel: MainViewModel,
    isSideMenuOpen: Boolean
): Modifier = this.onPreviewKeyEvent { event ->

    val currentTime = System.currentTimeMillis()

    when (event.type) {
        KeyEventType.KeyDown -> {
            when (event.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!isSideMenuOpen) {
                        event.nativeKeyEvent.startTracking()
                        if (currentTime - LAST_KEY_DOWN_TIME < 300) {
                            viewModel.setIsGridMoving(true)
                            return@onPreviewKeyEvent true
                        } else {
                            LAST_KEY_DOWN_TIME = currentTime
                        }
                    }
                }
            }
        }

        KeyEventType.KeyUp -> {
            try {
                if (event.nativeKeyEvent.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS == 0) {
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                            viewModel.setIsGridMoving(false)
                            return@onPreviewKeyEvent true
                        }

                        else -> {
                            viewModel.setIsGridMoving(false)
                        }
                    }
                }
            } catch (e: Exception) {
                LimeLog.warning("AppGrid Error in onKeyUp $e")
            }
        }
    }
    return@onPreviewKeyEvent false
}