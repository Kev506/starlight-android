package com.starlight.ui.screens

import android.app.Service
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.starlight.ui.helpers.DataCollectionHelper
import com.starlight.ui.helpers.UiHelper
import com.starlight.ui.modifiers.handleKeyEvent
import com.starlight.utils.LimeLog
import com.starlight.utils.LimeLog.warning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds


@Composable
fun DataCollectionScreen(
    viewModel: MainViewModel
) {
    val activity = LocalActivity.current as ComponentActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val dataLoader = remember { DataCollectionHelper(viewModel, activity) }
    dataLoader.uiContext = activity
    viewModel.setDataCollectionHelper(dataLoader)

    LaunchedEffect(Unit) {
        viewModel.setLoaderValue(true)
    }

    DisposableEffect(lifecycleOwner) {
        val bound = activity.bindService(
            Intent(
                activity,
                ComputerManagerService::class.java
            ), dataLoader.serviceConnection, Service.BIND_AUTO_CREATE
        )
        onDispose {
            if (bound) {
                try {
                    activity.unbindService(dataLoader.serviceConnection)
                } catch (e: IllegalArgumentException) {
                    warning("Error unbinding service connect $e")
                }
            }
        }
    }

    DataCollectionScreenContent(
        viewModel = viewModel,
        dataLoader = dataLoader
    )
}

@Composable
fun DataCollectionScreenContent(
    viewModel: MainViewModel,
    dataLoader: DataCollectionHelper?
) {

    var targetProgress by remember { mutableFloatStateOf(0f) }

    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(
            durationMillis = 500,
            easing = LinearEasing
        )
    )

    val showLoader = viewModel.showLoaderState.observeAsState(true)
    val activity = LocalActivity.current as ComponentActivity
    val focusRequester = remember { FocusRequester() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    LifecycleResumeEffect(key1 = lifecycleOwner) {
       coroutineScope.launch(Dispatchers.IO) {
            UiHelper.reportDecoderCrash(activity, viewModel)
        }

        dataLoader?.startComputerUpdates()

        onPauseOrDispose {
            dataLoader?.stopComputerUpdates()
            viewModel.setIsMenuRequired(true)
            viewModel.setLoaderValue(false)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = Color.Transparent)
    ) {
        ConstraintLayout(
            modifier = Modifier
                .fillMaxSize()
                .handleKeyEvent(
                    viewModel = viewModel,
                    parent = activity,
                    isSideMenuOpen = false
                )
                .focusRequester(focusRequester)
                .focusable()
        ) {

            val (layoutReference, background, text) = createRefs()

            val guidelineBaselineRef = createGuidelineFromTop(0.47f)

            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(R.drawable.portrait_mode)
                    .crossfade(false)
                    .allowHardware(true)
                    .build(),
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

            LaunchedEffect(Unit) {
                while (targetProgress < 1f) {
                    delay(500.milliseconds)
                    targetProgress += 0.01f
                }
                LimeLog.info("Created database from applist")
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .constrainAs(layoutReference) {
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                        start.linkTo(parent.start)
                        end.linkTo(parent.end)
                    },
                contentAlignment = Alignment.Center
            ) {
                when {
                    showLoader.value -> {
                        CircularProgressIndicator(
                            progress = { animatedProgress },
                            strokeCap = StrokeCap.Round,
                            color = Color(0xFF33B5E5),
                            modifier = Modifier
                                .size(150.dp)
                        )

                        Text(
                            text = "${(animatedProgress * 100).toInt()}%",
                            fontSize = 28.sp,
                            color = White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.wrapContentSize()
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 10.dp)
                    .background(Color.Transparent)
                    .focusProperties { canFocus = false }
                    .constrainAs(text) {
                        top.linkTo(guidelineBaselineRef)
                        bottom.linkTo(parent.bottom)
                        start.linkTo(parent.start)
                        end.linkTo(parent.end)
                    },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Text(
                    text = stringResource(R.string.data_collection),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.wrapContentSize()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = stringResource(R.string.loading_for_first_time_use_please_wait),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.wrapContentSize()
                )
            }
        }
    }
}