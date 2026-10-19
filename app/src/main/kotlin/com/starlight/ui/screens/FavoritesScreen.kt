package com.starlight.ui.screens

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.placeholder
import com.starlight.R
import com.starlight.games.GameData
import com.starlight.games.GameDetailsState
import com.starlight.models.GameViewModel
import com.starlight.models.MainViewModel
import com.starlight.preferences.PreferenceKeys.dataStoreConfigFlow
import com.starlight.ui.composables.StarRatingBar
import com.starlight.ui.composables.StepSize
import com.starlight.ui.helpers.TranslatorHelper
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * Created by Kevin Andrews on 03/05/2026.
 */

@Composable
fun FavoritesScreen(
    viewModel: MainViewModel,
    content: @Composable () -> Unit
) {
    val activity = LocalActivity.current as? ComponentActivity ?: return

    val gameViewModel = viewModel<GameViewModel> {
        val repository = GameData(
            activity.applicationContext,
            activity
        )
        GameViewModel(repository)
    }

    val context = LocalContext.current
    val resources = LocalResources.current
    var gameState by remember { mutableStateOf(GameDetailsState()) }
    val gameData by gameViewModel.gameDetails.observeAsState()
    val selectedApp by viewModel.selectedApp.observeAsState(null)

    val config by context.dataStoreConfigFlow.collectAsStateWithLifecycle(initialValue = null)

    config?.let { _ ->

        val composeData = remember {
            TranslatorHelper(
                resources = resources,
                viewModel = viewModel,
                uiContext = activity
            )
        }

        key(selectedApp?.app?.id) {
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
                        backgroundUrl = uri.toString()
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
                        coverUrl = data.coverArt
                    )
                }
            }
        }

        FavoritesScreenContent(
            viewModel = viewModel,
            state = gameState,
            content = content
        )
    }
}

@Composable
fun FavoritesScreenContent(
    viewModel: MainViewModel,
    state: GameDetailsState,
    content: @Composable () -> Unit
) {

    val isMoving by viewModel.isGridMoving.observeAsState(false)
    val favsViewHelper by viewModel.favsHelper.observeAsState()

    var currentTime by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            delay(1000.milliseconds)
        }
    }

    val detailAlpha by animateFloatAsState(
        targetValue = if (isMoving) 0.3f else 1f,
        animationSpec = tween(300)
    )

    LaunchedEffect(isMoving) {
        if (!isMoving) {
            favsViewHelper?.handleGameCheck()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = Color.Transparent)
    ) {
        ConstraintLayout(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {

            val (banner, gradHoriz, gradBottom, gradRight, title, clock, listContainer) = createRefs()

            val guidelineCenter = createGuidelineFromTop(0.5f)
            val guidelineStart = createGuidelineFromAbsoluteLeft(0.01f)
            val guidelineEnd = createGuidelineFromAbsoluteRight(0.03f)

            if (!isMoving) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(state.backgroundUrl)
                        .placeholder(R.drawable.portrait_mode)
                        .crossfade(false)
                        .allowHardware(true)
                        .build(),
                    error = painterResource(id = R.drawable.portrait_mode),
                    contentDescription = null,
                    modifier = Modifier
                        .constrainAs(banner) {
                            top.linkTo(parent.top)
                            bottom.linkTo(guidelineCenter)
                            end.linkTo(parent.end)
                            width = Dimension.percent(0.6f)
                            height = Dimension.ratio("16:9")
                        }
                        .padding(top = 15.dp)
                        .graphicsLayer(alpha = detailAlpha),
                    contentScale = ContentScale.Crop
                )
            }

            Box(
                modifier = Modifier
                    .constrainAs(gradHoriz) {
                        start.linkTo(guidelineStart)
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                        width = Dimension.percent(0.7f)
                        height = Dimension.fillToConstraints
                    }
                    .paint(rememberAsyncImagePainter(R.drawable.banner_gradient))
            )

            Box(
                modifier = Modifier
                    .constrainAs(gradBottom) {
                        bottom.linkTo(banner.bottom)
                        top.linkTo(parent.top)
                        end.linkTo(parent.end)
                        width = Dimension.percent(0.6f)
                        height = Dimension.fillToConstraints
                    }
                    .paint(rememberAsyncImagePainter(R.drawable.gradient_vertical))
            )

            Box(
                modifier = Modifier
                    .constrainAs(gradRight) {
                        end.linkTo(parent.end)
                        top.linkTo(parent.top)
                        bottom.linkTo(banner.bottom)
                        width = Dimension.percent(0.6f)
                        height = Dimension.fillToConstraints
                    }
                    .paint(rememberAsyncImagePainter(R.drawable.gradient_right))
            )

            if (!isMoving) {
                Text(
                    text = currentTime,
                    color = White,
                    fontSize = 16.sp,
                    modifier = Modifier.constrainAs(clock) {
                        top.linkTo(parent.top, 30.dp)
                        end.linkTo(guidelineEnd, 40.dp)
                    }
                )
            }


            Column(
                modifier = Modifier.constrainAs(title) {
                    start.linkTo(guidelineStart, margin = 25.dp)
                    top.linkTo(parent.top, 30.dp)
                }
                    .graphicsLayer(alpha = detailAlpha),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.Start,
            ) {

                Text(
                    text = state.title ?: "",
                    color = White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(14.dp))

                if (!isMoving) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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

                    Spacer(modifier = Modifier.height(14.dp))

                    if (state.ratingValue != null) {
                        when (state.ratingValue) {
                            "Teen" -> {
                                Image(
                                    painterResource(id = R.drawable.teen),
                                    contentDescription = "Teen Rating",
                                    modifier = Modifier
                                        .width(30.dp)
                                        .height(60.dp)
                                )
                            }

                            "Mature" -> {
                                Image(
                                    painterResource(id = R.drawable.mature17),
                                    contentDescription = "Mature Rating",
                                    modifier = Modifier
                                        .width(30.dp)
                                        .height(60.dp)
                                )
                            }

                            "Everyone" -> {
                                Image(
                                    painterResource(id = R.drawable.evereyone),
                                    contentDescription = "Everyone Rating",
                                    modifier = Modifier
                                        .width(30.dp)
                                        .height(60.dp)
                                )
                            }

                            "Everyone 10+" -> {
                                Image(
                                    painterResource(id = R.drawable.everyone10),
                                    contentDescription = "Everyone 18 Rating",
                                    modifier = Modifier
                                        .width(30.dp)
                                        .height(60.dp)
                                )
                            }

                            "Adults Only" -> {
                                Image(
                                    painterResource(id = R.drawable.adult18),
                                    contentDescription = "Adult Rating",
                                    modifier = Modifier
                                        .width(30.dp)
                                        .height(60.dp)
                                )
                            }

                            "Rating Pending" -> {
                                Image(
                                    painterResource(id = R.drawable.unrated),
                                    contentDescription = "Unrated Rating",
                                    modifier = Modifier
                                        .width(30.dp)
                                        .height(60.dp)
                                )
                            }

                            else -> {
                                Image(
                                    painterResource(id = R.drawable.unrated),
                                    contentDescription = "Unrated Rating",
                                    modifier = Modifier
                                        .width(30.dp)
                                        .height(60.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        if (state.isFavorite) {
                            Image(
                                painterResource(id = R.drawable.baseline_favorite_24_red),
                                contentDescription = "Favorite"
                            )
                        }

                        Spacer(modifier = Modifier.width(if (state.isFavorite) 12.dp else 0.dp))

                        if (state.ratingText != null) {
                            val ratingValue = state.ratingText!!.take(1).toIntOrNull() ?: 0
                            val value = (ratingValue.toFloat() / 2f)

                            StarRatingBar(
                                value = value,
                                modifier = Modifier
                                    .background(Color.Transparent),
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
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (!state.description.isNullOrEmpty()) {
                        Text(
                            text = state.description?.take(150)?.plus(" ...")
                                ?: stringResource(R.string.No_Details),
                            color = White,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .width(400.dp)
                                .wrapContentSize()
                        )
                    }
                }
            }

            Box(
                modifier = Modifier.constrainAs(listContainer) {
                    top.linkTo(guidelineCenter)
                    bottom.linkTo(parent.bottom)
                    start.linkTo(parent.start, 25.dp)
                    end.linkTo(parent.end)
                    width = Dimension.fillToConstraints
                    height = Dimension.value(200.dp)
                }
            ) {
                content()
            }
        }
    }
}