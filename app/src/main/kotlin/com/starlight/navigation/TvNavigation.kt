package com.starlight.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.starlight.computers.ComputerManagerService.ComputerManagerBinder
import com.starlight.models.MainViewModel
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.ui.screens.GameDetailsScreen
import com.starlight.ui.screens.GameScreen
import com.starlight.utils.AppComputerObject

/**
 * Created by Kevin Andrews on 20/03/2026.
 */

@Composable
fun TvNavigation(
    app: AppComputerObject,
    computer: ComputerDetails,
    managerBinder: ComputerManagerBinder
) {
    val navController = rememberNavController()
    val viewModel = viewModel<MainViewModel>()

    val ressumedSession by viewModel.resumeSession.observeAsState(false)
    viewModel.setNavController(navController)

    NavHost(
        navController = navController,
        enterTransition = { slideInHorizontally(initialOffsetX = { 500 }) + fadeIn() },
        exitTransition = { slideOutHorizontally(targetOffsetX = { -500 }) + fadeOut() },
        popEnterTransition = { slideInHorizontally(initialOffsetX = { -500 }) + fadeIn() },
        popExitTransition = { slideOutHorizontally(targetOffsetX = { 500 }) + fadeOut() },
        sizeTransform = { SizeTransform { _, _ -> SnapSpec() } },
        startDestination = if (ressumedSession) Screens.GameStreamingScreen.route else Screens.DetailsScreen.route) {

        composable(Screens.DetailsScreen.route) {
            viewModel.setScreenState("DetailsScreen")

            GameDetailsScreen(
                app = app,
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

    }
}