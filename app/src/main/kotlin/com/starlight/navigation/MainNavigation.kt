package com.starlight.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.starlight.models.MainViewModel
import com.starlight.preferences.PreferenceKeys.dataStoreConfigFlow
import com.starlight.ui.grids.FavoritesAppGrid
import com.starlight.ui.grids.HomeAppGrid
import com.starlight.ui.screens.AddComputerScreen
import com.starlight.ui.screens.AppViewScreen
import com.starlight.ui.screens.ComputerScreen
import com.starlight.ui.screens.DataCollectionScreen
import com.starlight.ui.screens.FavoritesScreen
import com.starlight.ui.screens.GameDetailsScreen
import com.starlight.ui.screens.GameScreen
import com.starlight.ui.screens.HelpScreen
import com.starlight.ui.screens.HomeScreen
import com.starlight.ui.screens.OptionsScreen
import com.starlight.ui.screens.SettingsScreen
import com.starlight.ui.screens.VideoGridScreen
import com.starlight.utils.Constants.APP_VIEW_KEY_SELECTED_POSITION
import com.starlight.utils.Constants.KEY_FAV_SELECTED_POSITION
import com.starlight.utils.Constants.KEY_SELECTED_POSITION
import com.starlight.utils.Starlight.Companion.dataStore
import com.starlight.video_player.player.ui.screens.YouTubePlayerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainNavigation(
    isLeanbackEnabled: Boolean
) {

    val activity = LocalActivity.current as ComponentActivity
    val viewModel = viewModel<MainViewModel>()
    val refreshId by viewModel.refreshId.observeAsState(0)
    val navController = rememberNavController()
    viewModel.setNavController(navController)
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val tvBringIntoViewSpec = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float
            ): Float {
                val targetCenter = containerSize / 2f
                val currentCenter = offset + (size / 2f)
                return currentCenter - targetCenter
            }
        }
    }

    NavHost(
        modifier = Modifier,
        navController = navController,
        enterTransition = { slideInHorizontally(initialOffsetX = { 500 }) + fadeIn() },
        exitTransition = { slideOutHorizontally(targetOffsetX = { -500 }) + fadeOut() },
        popEnterTransition = { slideInHorizontally(initialOffsetX = { -500 }) + fadeIn() },
        popExitTransition = { slideOutHorizontally(targetOffsetX = { 500 }) + fadeOut() },
        sizeTransform = { SizeTransform { _, _ -> SnapSpec() } },
        startDestination = Screens.HomeScreen.route
    )
    {

        composable(Screens.HomeScreen.route) {
            viewModel.setScreenState("ComputerScreen")

            key(refreshId) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides tvBringIntoViewSpec) {
                    ComputerScreen(
                        viewModel = viewModel
                    )
                }
            }
        }

        composable(Screens.AddPcScreen.route) {
            viewModel.setScreenState("AddComputerScreen")

            AddComputerScreen(
                viewModel = viewModel
            )
        }

        composable(Screens.DataCollectionScreen.route) {
            viewModel.setScreenState("DataCollectionScreen")

            DataCollectionScreen(
                viewModel = viewModel
            )
        }

        composable(Screens.MainScreen.route) {
            viewModel.setScreenState("MainScreen")

            key(refreshId) {
                HomeScreen(
                    viewModel = viewModel,
                    content = {
                        CompositionLocalProvider(LocalBringIntoViewSpec provides tvBringIntoViewSpec) {
                            HomeAppGrid(
                                viewModel = viewModel,
                                computer = viewModel.computer,
                                uuid = viewModel.uuidString!!
                            )
                        }
                    }
                )

            }
        }

        composable(Screens.FavoritesScreen.route) {
            viewModel.setScreenState("FavoritesScreen")

            FavoritesScreen(
                viewModel = viewModel,
                content = {
                    CompositionLocalProvider(LocalBringIntoViewSpec provides tvBringIntoViewSpec) {
                        FavoritesAppGrid(
                            viewModel = viewModel,
                            computer = viewModel.computer,
                            uuid = viewModel.uuidString!!
                        )
                    }
                }
            )
        }

        composable(Screens.AppViewScreen.route) {
            viewModel.setScreenState("AppViewScreen")

            key(refreshId) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides tvBringIntoViewSpec) {
                    AppViewScreen(
                        viewModel = viewModel,
                        computer = viewModel.computer,
                        uuid = viewModel.uuidString!!
                    )
                }
            }
        }

        composable(Screens.DetailsScreen.route) {
            viewModel.setScreenState("DetailsScreen")

            val managerBinder by viewModel.binder.observeAsState(null)
            val selectedApp by viewModel.selectedApp.observeAsState(null)
            val computer by viewModel.getComputer.observeAsState(null)

            GameDetailsScreen(
                app = selectedApp,
                computer = computer,
                binder = managerBinder,
                viewModel = viewModel
            )
        }

        composable(
            Screens.GameStreamingScreen.route,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
            sizeTransform = { SizeTransform { _, _ -> SnapSpec() } }) {
            viewModel.setScreenState("GameStreamingScreen")

            GameScreen(
                viewModel = viewModel,
                appId = viewModel.appId!!,
                appName = viewModel.appName!!,
                computer = viewModel.computer!!,
                binder = viewModel.managerBinder!!,
                uuid = viewModel.uuidString!!,
                isHdrSupported = viewModel.isHdrSupported,
                pcName = viewModel.pcName!!,
                uniqueIdString = viewModel.uniqueIdString!!,
                cert = viewModel.cert,
                app = viewModel.app!!
            )
        }

        composable(Screens.SettingsScreen.route) {
            viewModel.setScreenState("SettingsScreen")

            CompositionLocalProvider(LocalBringIntoViewSpec provides tvBringIntoViewSpec) {
                OptionsScreen(
                    viewModel = viewModel
                )
            }
        }

        composable(Screens.HelpScreen.route) {
            viewModel.setScreenState("HelpScreen")

            viewModel.url?.let { it1 ->
                HelpScreen(
                    url = it1,
                    viewModel = viewModel
                )
            }
        }

        composable(Screens.MainSettingsScreen.route) {
            viewModel.setScreenState("MainSettingsScreen")

            key(refreshId) {
                SettingsScreen(
                    viewModel = viewModel
                )
            }
        }

        composable(Screens.TrailerPlayerScreen.route) {
            viewModel.setScreenState("TrailerScreen")

            val config by context.dataStoreConfigFlow.collectAsStateWithLifecycle(initialValue = null)
            val videoId by viewModel.videoId.observeAsState("")

            config?.let { currentConfig ->
                YouTubePlayerScreen(
                    videoId = videoId,
                    areCaptionsVisible = currentConfig.areCaptionsVisible,
                    language = currentConfig.language,
                    setBackInProgress = { inProgress ->
                        viewModel.setBackInProgress(inProgress)
                    }
                )
            }
        }

        composable(Screens.VideoScreen.route) {
            viewModel.setScreenState("VideoScreen")

            CompositionLocalProvider(LocalBringIntoViewSpec provides tvBringIntoViewSpec) {
                VideoGridScreen(
                    videosFromDb = viewModel.videos,
                    onVideoSelectedToPlay = {
                        navController.navigate(Screens.TrailerPlayerScreen.route)
                        viewModel.setVideoId(it)
                        viewModel.setIsMenuRequired(false)
                    },
                    viewModel = viewModel
                )
            }
        }

        composable(Screens.Exit.route) {

            val config by activity.dataStoreConfigFlow.collectAsStateWithLifecycle(initialValue = null)

            config?.let { currentConfig ->
                if (currentConfig.favSelectedPosition != 0) {
                    LaunchedEffect(Unit) {
                       coroutineScope.launch(Dispatchers.IO) {
                            activity.dataStore.edit { preferences ->
                                preferences[intPreferencesKey(KEY_FAV_SELECTED_POSITION)] = 0
                            }
                        }
                    }
                }
                if (currentConfig.selectedPosition != 0) {
                    LaunchedEffect(Unit) {
                       coroutineScope.launch(Dispatchers.IO) {
                            activity.dataStore.edit { preferences ->
                                preferences[intPreferencesKey(KEY_SELECTED_POSITION)] = 0
                            }
                        }
                    }
                }

                if (!isLeanbackEnabled) {
                    if (currentConfig.appViewSelectedPosition != 0) {
                        LaunchedEffect(Unit) {
                           coroutineScope.launch(Dispatchers.IO) {
                                activity.dataStore.edit { preferences ->
                                    preferences[intPreferencesKey(APP_VIEW_KEY_SELECTED_POSITION)] =
                                        0
                                }
                            }
                        }
                    }
                }

                activity.finishAffinity()
            }
        }
    }
}