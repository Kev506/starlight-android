package com.starlight.ui.screens

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.paint
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.placeholder
import com.starlight.R
import com.starlight.computers.ComputerManagerService
import com.starlight.games.GameData
import com.starlight.games.GameDetailsState
import com.starlight.models.GameViewModel
import com.starlight.models.MainViewModel
import com.starlight.navigation.Screens
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.preferences.PreferenceKeys.dataStoreConfigFlow
import com.starlight.ui.composables.StarRatingBar
import com.starlight.ui.composables.StepSize
import com.starlight.ui.helpers.DetailsLoaderHelper
import com.starlight.ui.helpers.DetailsLoaderHelper.Companion.loader
import com.starlight.ui.helpers.ServerHelper
import com.starlight.ui.helpers.TranslatorHelper
import com.starlight.ui.modifiers.handleLeftEvent
import com.starlight.ui.modifiers.handleScrollEvent
import com.starlight.utils.AppComputerObject
import com.starlight.utils.Constants.IGDB_URL
import kotlinx.coroutines.launch

@Composable
fun GameDetailsScreen(
    app: AppComputerObject?,
    computer: ComputerDetails?,
    binder: ComputerManagerService.ComputerManagerBinder?,
    viewModel: MainViewModel
) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val context = LocalContext.current
    val resources = LocalResources.current

    val gameViewModel = viewModel<GameViewModel> {
        val repository = GameData(
            activity.applicationContext,
            activity
        )
        GameViewModel(repository)
    }

    val composeData = remember {
        TranslatorHelper(
            resources = resources,
            viewModel = viewModel,
            uiContext = activity
        )
    }

    var gameState by remember { mutableStateOf(GameDetailsState(isLoading = true)) }
    val gameData by gameViewModel.gameDetails.observeAsState()

    val config by context.dataStoreConfigFlow.collectAsStateWithLifecycle(initialValue = null)

    config?.let { _ ->

        LaunchedEffect(Unit) {
            if (!composeData.doesDatabaseExist(context)) {
                val desc = composeData.displayTranslatedDescription(
                    gameState,
                    activity.getString(R.string.No_Details),
                    composeData.getValues().third,
                    composeData.getValues().second!!
                )
                val uri =
                    ("android.resource://" + context.packageName + "/drawable/portrait_mode").toUri()
                gameState = gameState.copy(
                    description = desc,
                    backgroundUrl = uri.toString(),
                    isTVScreen = viewModel.getTVScreenState(),
                    isLoading = false
                )
            }
        }

        LaunchedEffect(gameData) {
            gameData?.let { data ->
                val desc = composeData.displayTranslatedDescription(
                    gameState,
                    data.description,
                    composeData.getValues().third,
                    composeData.getValues().second!!
                )
                val uri =
                    ("android.resource://" + context.packageName + "/drawable/portrait_mode").toUri()

                gameState = gameState.copy(
                    developer = data.developers,
                    description = desc,
                    title = data.title,
                    releaseYear = data.released,
                    ratingValue = data.esrb_rating,
                    ratingText = data.metacritic,
                    backgroundUrl = if (data.background_image != null) data.background_image else uri.toString(),
                    isFavorite = viewModel.isFavs,
                    coverUrl = data.coverArt,
                    fallbackCoverArt = data.fallbackCoverArt,
                    isTVScreen = viewModel.getTVScreenState(),
                    video = data.video,
                    slug = data.slug,
                    isLoading = false // Data loaded
                )
            }
        }

        if (!gameState.isLoading) {
            GameDetailsContent(
                state = gameState,
                computer = computer,
                app = app,
                binder = binder,
                viewModel = viewModel
            )
        }

        app?.let { gameViewModel.loadGame(it) }
    }
}

@Composable
fun GameDetailsContent(
    state: GameDetailsState,
    computer: ComputerDetails?,
    app: AppComputerObject?,
    binder: ComputerManagerService.ComputerManagerBinder?,
    viewModel: MainViewModel
) {
    val playButtonRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val detailsLoader =
        remember { DetailsLoaderHelper(computer!!, viewModel.uniqueIdString!!, context) }

    DisposableEffect (lifecycleOwner) {
        onDispose {
            if (loader != null) {
                detailsLoader.cancelQueuedOperations()
            }
        }
    }

    val imageState = produceState<Any?>(initialValue = null, key1 = app?.app) {
        value = app?.app?.let {
            if (detailsLoader.getGameImage(it, context) != null)
                detailsLoader.getGameImage(it, context) else loader?.getGameImage(it)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = Color.Transparent)
    ) {
        ConstraintLayout(
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged {
                    if (it.hasFocus) {
                        viewModel.setBackInProgress(false)
                    }
                }
                .background(Color.Black)
        ) {

            val (loading, background, maskView, gameImage, playButton, gameTitle, rating) = createRefs()
            val (yearIcon, igdbImg, gridDetails) = createRefs()

            val activity = LocalActivity.current as? ComponentActivity
            var focusState by remember { mutableStateOf(false) }
            val scrollState = rememberScrollState()
            val scrollAmount = 50f // Adjust scroll speed
            val focusManager = LocalFocusManager.current

            // Background Image
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(state.backgroundUrl)
                    .placeholder(R.drawable.portrait_mode)
                    .crossfade(false)
                    .allowHardware(true)
                    .build(),
                error = painterResource(id = R.drawable.portrait_mode),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .constrainAs(background) {
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                        start.linkTo(parent.start)
                        end.linkTo(parent.end)
                    }
            )

            // Guidelines
            val guidelineStartRef = createGuidelineFromTop(0.31f)
            val guidelineCenterRef = createGuidelineFromTop(0.5f)
            val guidelineBaselineRef = createGuidelineFromTop(0.67f)
            val guidelineVerticalRef = createGuidelineFromStart(0.17f)

            // Gradient Mask
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .paint(
                        painterResource(R.drawable.leanback_mask),
                        contentScale = ContentScale.FillBounds
                    )
                    .alpha(0.4f)
                    .constrainAs(maskView) {
                        top.linkTo(guidelineCenterRef)
                        bottom.linkTo(parent.bottom)
                        start.linkTo(parent.start)
                        end.linkTo(parent.end)
                        height = Dimension.fillToConstraints
                    }
            )

            // Game Cover Image
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageState.value)
                    .crossfade(false)
                    .allowHardware(true)
                    .build(),
                contentDescription = state.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .clip(
                        shape = RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = 16.dp,
                            bottomEnd = 16.dp
                        )
                    )
                    .clipToBounds()
                    .width(150.dp)
                    .height(190.dp)
                    .constrainAs(gameImage) {
                        top.linkTo(guidelineStartRef)
                        start.linkTo(parent.start, margin = 10.dp)
                        bottom.linkTo(playButton.top, margin = 10.dp)
                    }
            )

            // Play Button
            Button(
                onClick = {
                    app?.let { it1 ->
                        computer?.let { it2 ->
                            binder?.let { it3 ->
                                it1.app?.let { it4 ->
                                    ServerHelper.doStartStream(
                                        parent = activity!!,
                                        app = it4,
                                        computer = it2,
                                        binder = it3,
                                        viewModel = viewModel
                                    )
                                }
                            }
                        }
                    }
                },
                scale = ButtonDefaults.scale(focusedScale = 1f),
                colors = ButtonDefaults.colors(
                    containerColor = Color(0x6D0099CC),
                    disabledContainerColor = Color(0xFFBDBDBD),
                    focusedContainerColor = Color(0xFF33B5E5),
                    pressedContainerColor = Color(0xC9020122),
                    contentColor = White
                ),
                modifier = Modifier
                    .width(150.dp)
                    .height(50.dp)
                    .defaultMinSize(150.dp, 50.dp)
                    .focusRequester(playButtonRequester)
                    .constrainAs(playButton) {
                        top.linkTo(guidelineBaselineRef, margin = 10.dp)
                        start.linkTo(parent.start, margin = 10.dp)
                    },
                content = {
                    Text(
                        stringResource(id = R.string.play).uppercase(),
                        fontSize = 14.sp,
                        color = White,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .weight(1f)
                            .align(Alignment.CenterVertically)
                            .padding(top = 5.dp)
                    )
                })

            // Game Title
            if (!state.title.isNullOrEmpty()) {
                Text(
                    text = state.title!!,
                    fontSize = 24.sp,
                    color = White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.constrainAs(gameTitle) {
                        top.linkTo(guidelineCenterRef, margin = 15.dp)
                        start.linkTo(guidelineVerticalRef, margin = 30.dp)
                        bottom.linkTo(yearIcon.top, margin = 10.dp)
                    }
                )
            }
            if (state.ratingValue != null) {
                when (state.ratingValue) {
                    "Teen" -> {
                        Image(
                            painterResource(id = R.drawable.teen),
                            contentDescription = "Teen Rating",
                            modifier = Modifier
                                .width(15.dp)
                                .height(30.dp)
                                .constrainAs(rating) {
                                    start.linkTo(gameTitle.end, margin = 10.dp)
                                    top.linkTo(gameTitle.top)
                                }
                        )
                    }

                    "Mature" -> {
                        Image(
                            painterResource(id = R.drawable.mature17),
                            contentDescription = "Mature Rating",
                            modifier = Modifier
                                .width(15.dp)
                                .height(30.dp)
                                .constrainAs(rating) {
                                    start.linkTo(gameTitle.end, margin = 10.dp)
                                    top.linkTo(gameTitle.top)
                                }
                        )
                    }

                    "Everyone" -> {
                        Image(
                            painterResource(id = R.drawable.evereyone),
                            contentDescription = "Everyone Rating",
                            modifier = Modifier
                                .width(15.dp)
                                .height(30.dp)
                                .constrainAs(rating) {
                                    start.linkTo(gameTitle.end, margin = 10.dp)
                                    top.linkTo(gameTitle.top)
                                }
                        )
                    }

                    "Everyone 10+" -> {
                        Image(
                            painterResource(id = R.drawable.everyone10),
                            contentDescription = "Everyone 18 Rating",
                            modifier = Modifier
                                .width(15.dp)
                                .height(30.dp)
                                .constrainAs(rating) {
                                    start.linkTo(gameTitle.end, margin = 10.dp)
                                    top.linkTo(gameTitle.top)
                                }
                        )
                    }

                    "Adults Only" -> {
                        Image(
                            painterResource(id = R.drawable.adult18),
                            contentDescription = "Adult Rating",
                            modifier = Modifier
                                .width(15.dp)
                                .height(30.dp)
                                .constrainAs(rating) {
                                    start.linkTo(gameTitle.end, margin = 10.dp)
                                    top.linkTo(gameTitle.top)
                                }
                        )
                    }

                    "Rating Pending" -> {
                        Image(
                            painterResource(id = R.drawable.unrated),
                            contentDescription = "Unrated Rating",
                            modifier = Modifier
                                .width(15.dp)
                                .height(30.dp)
                                .constrainAs(rating) {
                                    start.linkTo(gameTitle.end, margin = 10.dp)
                                    top.linkTo(gameTitle.top)
                                }
                        )
                    }

                    else -> {
                        Image(
                            painterResource(id = R.drawable.unrated),
                            contentDescription = "Unrated Rating",
                            modifier = Modifier
                                .width(15.dp)
                                .height(30.dp)
                                .constrainAs(rating) {
                                    start.linkTo(gameTitle.end, margin = 10.dp)
                                    top.linkTo(gameTitle.top)
                                }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.constrainAs(yearIcon) { // Constrain the whole row
                    start.linkTo(guidelineVerticalRef, margin = 30.dp)
                    top.linkTo(gameTitle.bottom, margin = 10.dp)
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.isFavorite) {
                    Image(
                        painterResource(id = R.drawable.baseline_favorite_24_red),
                        contentDescription = "Favorite"
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                if (!state.releaseYear.isNullOrEmpty()) {
                    Image(
                        painterResource(id = R.drawable.ic_baseline_calendar_today_24),
                        contentDescription = "Release Year"
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = state.releaseYear!!,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = White
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                if (state.ratingText != null) {
                    val ratingValue = state.ratingText!!.take(1).toIntOrNull() ?: 0
                    val value = (ratingValue.toFloat() / 2f)

                    StarRatingBar(
                        value = value,
                        modifier = Modifier.background(Color.Transparent),
                        numOfSteps = 5,
                        stepSize = StepSize.HALF,
                        spaceBetween = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = state.ratingText!!,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = White
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                if (!state.developer.isNullOrEmpty()) {
                    Image(
                        painterResource(id = R.drawable.ic_baseline_people_24),
                        contentDescription = "Publisher"
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = state.developer!!,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = White
                    )
                }
            }
            if (!state.description.isNullOrEmpty()) {
                Text(
                    text = state.description!!,
                    fontSize = 14.sp,
                    fontWeight = if (focusState) FontWeight.Medium else FontWeight.Normal,
                    color = White,
                    modifier = Modifier
                        .constrainAs(gridDetails) {
                            top.linkTo(yearIcon.bottom, margin = 10.dp)
                            start.linkTo(guidelineVerticalRef, margin = 30.dp)
                            end.linkTo(parent.end, margin = 50.dp)
                            bottom.linkTo(parent.bottom, margin = 35.dp)
                            width = Dimension.fillToConstraints
                            height = Dimension.fillToConstraints
                        }
                        .padding(vertical = 12.dp)
                        .width(IntrinsicSize.Min)
                        .height(IntrinsicSize.Min)
                        .clipScrollableContainer(Orientation.Vertical)
                        .verticalScroll(state = scrollState)
                        .handleLeftEvent(targetFocus = focusManager)
                        .handleScrollEvent(
                            scrollState = scrollState,
                            scrollAmount = scrollAmount
                        )
                        .onFocusChanged {
                            focusState = it.isFocused
                            if (!focusState) {
                               coroutineScope.launch { scrollState.scrollTo(0) }
                            }
                        }
                        .focusable()
                )
            }

            // IGDB Provider section
            if (!state.isTVScreen) {
                Spacer(modifier = Modifier.height(5.dp))
                Button(
                    onClick = {
                        when {
                            detailsLoader.getGameTrailer(app!!.app, context).isNotEmpty() -> {
                                viewModel.setVideoId(state.video)
                                viewModel.getNavController()!!.navigate(Screens.TrailerPlayerScreen.route)
                                viewModel.setIsMenuRequired(false)
                            }
                            detailsLoader.getGameSlug(app.app, context).isNotEmpty() -> {
                                viewModel.url = "${IGDB_URL}games/${state.slug}"
                                viewModel.getNavController()!!.navigate(Screens.HelpScreen.route)
                                viewModel.setIsMenuRequired(false)
                            }
                            else -> {
                                viewModel.url = IGDB_URL
                                viewModel.getNavController()!!.navigate(Screens.HelpScreen.route)
                                viewModel.setIsMenuRequired(false)
                            }
                        }
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = White,
                        focusedContentColor = Color(0xFF00DDFF),
                        disabledContentColor = White,
                        focusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .width(150.dp)
                        .height(50.dp)
                        .defaultMinSize(150.dp, 50.dp)
                        .wrapContentSize(Alignment.Center, false)
                        .constrainAs(igdbImg) {
                            top.linkTo(playButton.bottom, margin = 15.dp)
                            start.linkTo(parent.start, margin = 10.dp)
                        },
                    content = {
                        Image(
                            painter = painterResource(id = R.drawable.igdb),
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            stringResource(
                                id = if (detailsLoader.getGameTrailer(app!!.app, context).isNotEmpty()
                                ) R.string.trailer else R.string.provider
                            ).uppercase(), fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .padding(start = 10.dp)
                        )
                    }
                )
            }

            // Loading Indicator
            if (state.isLoading) {
                CircularProgressIndicator(
                    color = Color(0xFF33B5E5),
                    modifier = Modifier
                        .size(50.dp)
                        .zIndex(10f)
                        .constrainAs(loading) {
                            top.linkTo(parent.top)
                            bottom.linkTo(parent.bottom)
                            start.linkTo(parent.start)
                            end.linkTo(parent.end)
                        }
                )
            }

            // Launch focus on the play button when the screen appears
            LaunchedEffect(Unit) {
                playButtonRequester.requestFocus()
            }
        }
    }
}
