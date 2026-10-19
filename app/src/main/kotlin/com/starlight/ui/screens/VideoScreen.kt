package com.starlight.ui.screens

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.MarqueeDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.StandardCardContainer
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.starlight.R
import com.starlight.models.DbVideoItem
import com.starlight.models.MainViewModel
import com.starlight.preferences.PreferenceKeys.dataStoreConfigFlow
import com.starlight.ui.modifiers.handleLeftEvent
import com.starlight.ui.modifiers.handleMainBackEvent
import com.starlight.ui.modifiers.handleMainNavigation
import com.starlight.ui.modifiers.onKeyDown
import com.starlight.utils.Constants.HQ_THUMBNAIL_URL
import com.starlight.utils.Constants.VIDEO_SELECTED_POSITION
import com.starlight.utils.Starlight.Companion.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.milliseconds

/**
 * Created by Kevin Andrews on 02/09/2026.
 */

@Composable
fun VideoGridScreen(
    videosFromDb: List<DbVideoItem>,
    onVideoSelectedToPlay: (String) -> Unit,
    viewModel: MainViewModel
) {
    val focusRequesters =
        remember(videosFromDb.size) { Array(videosFromDb.size) { FocusRequester() } }
    val isSideMenuOpen by viewModel.isSideMenuOpen.observeAsState(false)
    var focusTrigger by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val activity = LocalActivity.current as ComponentActivity
    val coroutineScope = rememberCoroutineScope()
    var isGridReady by remember { mutableStateOf(false) }
    val config by context.dataStoreConfigFlow.collectAsStateWithLifecycle(initialValue = null)

    val gridState = rememberLazyGridState()

    config?.let { currentConfig ->

        LaunchedEffect(videosFromDb.size, isSideMenuOpen, focusTrigger) {
            if (!isSideMenuOpen && videosFromDb.isNotEmpty()) {
                val target =
                    currentConfig.videoPosition.coerceIn(
                        0,
                        videosFromDb.lastIndex.coerceAtLeast(0)
                    )

                if (target >= 4) {
                    gridState.scrollToItem(target, 0)
                    yield()
                }

                focusRequesters.getOrNull(target)?.requestFocus()

                delay(50.milliseconds)
                isGridReady = true
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
                .padding(top = 30.dp, start = 24.dp, end = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.game_media_library),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )

            val target =
                currentConfig.videoPosition.coerceIn(0, videosFromDb.lastIndex.coerceAtLeast(0))

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(
                    top = 30.dp,
                    bottom = 30.dp
                ),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isGridReady) 1f else 0f)
                    .focusGroup()
                    .focusRestorer(focusRequesters.getOrNull(target) ?: FocusRequester.Default)
            ) {
                itemsIndexed(items = videosFromDb, key = { index, _ -> index }) { index, video ->

                    val focusRequester = focusRequesters[index]
                    var itemFocused by remember { mutableStateOf(false) }

                    StandardCardContainer(
                        imageCard = { interactionSource ->
                            Card(
                                modifier = Modifier
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
                                    .onKeyDown(
                                        viewModel = viewModel,
                                        isSideMenuOpen = isSideMenuOpen
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
                                        if (focusState.hasFocus) {
                                            viewModel.setBackInProgress(false)
                                        }
                                    }
                                    .padding(start = 1.dp, end = 1.dp, top = 1.dp, bottom = 1.dp)
                                    .padding(1.dp)
                                    .aspectRatio(16f / 9f),
                                onClick = {
                                    onVideoSelectedToPlay(video.videoId)

                                    coroutineScope.launch(Dispatchers.IO) {
                                        context.dataStore.edit { preferences ->
                                            preferences[intPreferencesKey(VIDEO_SELECTED_POSITION)] =
                                                index
                                        }
                                    }
                                },
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
                                ),
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
                                colors = CardDefaults.colors(
                                    containerColor = Color(0xFF1E1E1E),
                                    focusedContainerColor = Color(0xFF2A2A2A)
                                ),
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {

                                    val videoId = video.videoId

                                    AsyncImage(
                                        model = "$HQ_THUMBNAIL_URL$videoId/hqdefault.jpg",
                                        contentDescription = video.videoName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        },
                        title = {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = video.videoName,
                                color = Color.White,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = if (itemFocused) TextOverflow.Clip else TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .basicMarquee(
                                        iterations = if (itemFocused) MarqueeDefaults.Iterations else 0,
                                        animationMode = MarqueeAnimationMode.Immediately,
                                        initialDelayMillis = 1000
                                    )
                            )
                        }
                    )
                }
            }
        }
    }
}