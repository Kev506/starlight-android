@file:Suppress("SameParameterValue")

package com.starlight.activities

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.ContextMenu
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.placeholder
import com.starlight.R
import com.starlight.computers.ComputerManagerService
import com.starlight.games.GameDataBaseManager
import com.starlight.models.MainViewModel
import com.starlight.navigation.MainNavigation
import com.starlight.navigation.Screens
import com.starlight.navigation.TvNavigation
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.PairingManager
import com.starlight.preferences.PreferenceKeys.dataStoreConfigFlow
import com.starlight.ui.adapters.AppAdapter.Companion.favAppIds
import com.starlight.ui.adapters.AppAdapter.Companion.sunFavAppIds
import com.starlight.ui.composables.AppDrawer
import com.starlight.ui.dialogs.AlertDialog
import com.starlight.ui.dialogs.ProgressDialog
import com.starlight.ui.helpers.AddComputerHelper
import com.starlight.ui.helpers.AppViewHelper
import com.starlight.ui.helpers.ComputerHelper
import com.starlight.ui.helpers.DataCollectionHelper
import com.starlight.ui.helpers.FavoritesHelper
import com.starlight.ui.helpers.HomeScreenHelper
import com.starlight.ui.helpers.OptionsHelper
import com.starlight.ui.helpers.ServerHelper
import com.starlight.ui.helpers.TvHomeHelper
import com.starlight.ui.modifiers.LoadingOverlay
import com.starlight.ui.modifiers.handleKeyEvent
import com.starlight.ui.themes.StarlightTheme
import com.starlight.utils.AppComputerObject
import com.starlight.utils.Constants.ADD_FAVORITE_ID
import com.starlight.utils.Constants.DATABASE_PREF
import com.starlight.utils.Constants.DELETE_ID
import com.starlight.utils.Constants.FULL_APP_LIST_ID
import com.starlight.utils.Constants.GAMESTREAM_EOL_ID
import com.starlight.utils.Constants.GAME_STREAM_EOL_FAQ_URL
import com.starlight.utils.Constants.HIDE_APP_ID
import com.starlight.utils.Constants.PAIR_ID
import com.starlight.utils.Constants.QUIT_ID
import com.starlight.utils.Constants.SETUP_GUIDE_URL
import com.starlight.utils.Constants.START_OR_RESUME_ID
import com.starlight.utils.Constants.START_WITH_QUIT
import com.starlight.utils.Constants.TEST_NETWORK_ID
import com.starlight.utils.Constants.VIEW_COMPUTER_DETAILS_ID
import com.starlight.utils.Constants.VIEW_DETAILS_ID
import com.starlight.utils.Constants.WOL_ID
import com.starlight.utils.LimeLog
import com.starlight.utils.LimeLog.info
import com.starlight.utils.Starlight.Companion.dataStore
import com.starlight.utils.Starlight.Companion.quitGameJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.milliseconds


class MainView : ComponentActivity(), OptionsHelper.OptionsCallback,
    AppViewHelper.AppViewCallBack,
    FavoritesHelper.FavsScreenCallBack, HomeScreenHelper.HomeScreenCallBack,
    ComputerHelper.ComposeCallback, AddComputerHelper.ComposeCallback {

    private val viewModel: MainViewModel by viewModels()
    private var selectedApp: AppComputerObject? = null
    private var appViewHelper: AppViewHelper? = null
    private var homeViewHelper: HomeScreenHelper? = null
    private var favsHelper: FavoritesHelper? = null
    private var computer: ComputerHelper? = null
    private var dataLoader: DataCollectionHelper? = null
    private var tvHelper: TvHomeHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bundle = intent.getBundleExtra("appBundle")
        viewModel.isDatabaseCleared = bundle?.getBoolean("DATABASE_CLEARED") == true
        viewModel.computerChanged = bundle?.getBoolean("COMPUTER_CHANGED") == true

        viewModel.appId = intent.getStringExtra("AppId") ?: ""
        viewModel.uuidString = intent.getStringExtra("UUID") ?: ""
        viewModel.isHdrSupported = intent.getBooleanExtra("HDR", false) == true
        viewModel.appName = intent.getStringExtra("AppName") ?: ""

        setContent {

            val isNotDeepLink by viewModel.isNotDeepLink.observeAsState(false)

            if (viewModel.appId!!.isNotEmpty() && !isNotDeepLink) {
                val tvHomeHelper = remember {
                    TvHomeHelper(
                        viewModel = viewModel,
                        activity = this@MainView
                    )
                }
                tvHomeHelper.activity = this@MainView
                viewModel.setTvHelper(tvHomeHelper)
                viewModel.setTVScreenState(true)

                LaunchedEffect(Unit) {
                    this@MainView.bindService(
                        Intent(
                            this@MainView,
                            ComputerManagerService::class.java
                        ), tvHomeHelper.serviceConnection, BIND_AUTO_CREATE
                    )
                }
            }

            val mainContentRequester = remember { FocusRequester() }
            var isSideMenuOpen by remember { mutableStateOf(false) }
            val showLoader by viewModel.showAppLoaderState.observeAsState(false)
            val isMenuRequired by viewModel.isMenuRequired.observeAsState(true)
            val appViewHelper by viewModel.appViewHelper.observeAsState()
            val homeViewHelper by viewModel.homeViewHelper.observeAsState()
            val favsHelper by viewModel.favsHelper.observeAsState()
            val isBackInProgress by viewModel.isBackInProgress.observeAsState(false)
            val dataLoader by viewModel.dataCollectionHelper.observeAsState()
            val computer by viewModel.computerHelper.observeAsState()
            val tvHelper by viewModel.tvHelper.observeAsState()
            val databaseEnabled by viewModel.hasDataBase.observeAsState(false)
            val isMainView by viewModel.isMainView.observeAsState(false)
            val showProgressLoader by viewModel.showProgressDialog.observeAsState(false)
            val isQuitting by viewModel.isQuitting.observeAsState(false)
            val isAppRunning by viewModel.isAppRunning.observeAsState(false)
            val showDialog by viewModel.tvOpenAlertDialog.observeAsState(false)
            val isRequired by viewModel.isCancelRequired.observeAsState(false)
            val message by viewModel.dialogMessage.observeAsState("")
            val title by viewModel.dialogTitle.observeAsState("")
            val buttonLabel by viewModel.cancelButtonText.observeAsState("")
            val icon by viewModel.image.observeAsState(R.drawable.baseline_error_outline_24)
            val context = LocalContext.current
            val composeView = LocalView.current

            registerForContextMenu(composeView)

            val config by context.dataStoreConfigFlow.collectAsStateWithLifecycle(initialValue = null)

            LaunchedEffect(Unit) {
                if (!doesDatabaseExist(this@MainView) && databaseEnabled) {
                    this@MainView.dataStore.edit { preferences ->
                        preferences[booleanPreferencesKey(name = DATABASE_PREF)] = false
                    }
                }
            }

            LaunchedEffect(showLoader) {
                if (showLoader) {
                    delay(2500.milliseconds)
                    viewModel.setAppLoaderValue(false)
                }
            }

            LaunchedEffect(Unit) {
                viewModel.setAppLoaderValue(true)
            }

            this@MainView.computer = computer
            this@MainView.dataLoader = dataLoader
            this@MainView.appViewHelper = appViewHelper
            this@MainView.homeViewHelper = homeViewHelper
            this@MainView.favsHelper = favsHelper
            this@MainView.tvHelper = tvHelper

            config?.let { currentConfig ->

                LaunchedEffect(isSideMenuOpen) {
                    if (!isSideMenuOpen) {
                        mainContentRequester.requestFocus()
                    }
                }

                viewModel.setIsLeanbackEnabled(currentConfig.leanback)

                if (viewModel.name == "Sunshine") {
                    viewModel.setHasFavorites(currentConfig.sunFavorites.isNotEmpty())
                } else {
                    viewModel.setHasFavorites(currentConfig.favorites.isNotEmpty())
                }

                dataBaseManager = GameDataBaseManager.getInstance(this@MainView)

                viewModel.setIsStatsEnabled(currentConfig.statsEnabled)
                viewModel.setHasDataBase(currentConfig.databaseEnabled)

                if (!viewModel.getTVScreenState()) {

                    viewModel.setIsNotDeepLink(true)

                    StarlightTheme {
                        AppDrawer(
                            viewModel = viewModel,
                            mainContentRequester = mainContentRequester,
                            modifier = Modifier.focusProperties {
                                canFocus = isMenuRequired && (!isBackInProgress || isMainView || isSideMenuOpen)
                            },
                            content = {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(start = if (isMenuRequired) 70.dp else 0.dp)
                                        .handleKeyEvent(
                                            viewModel = viewModel,
                                            parent = this,
                                            isSideMenuOpen = isSideMenuOpen
                                        )
                                        .focusRequester(mainContentRequester)
                                        .focusable(),
                                    colors = SurfaceDefaults.colors(containerColor = Color.Transparent)
                                ) {

                                    if (!isMainView) {
                                        val backgroundImage = R.drawable.gaming_background

                                        // Background Image
                                        AsyncImage(
                                            model = ImageRequest.Builder(this@MainView)
                                                .data(backgroundImage)
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
                                        )
                                    }

                                    if (isMainView) {
                                        LoadingOverlay(isLoading = showLoader)
                                    }

                                    MainNavigation(
                                        isLeanbackEnabled = viewModel.getIsLeanbackEnabled()
                                    )
                                }
                            }
                        )
                    }
                } else {

                    StarlightTheme {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.portrait_mode),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .aspectRatio(16f / 9f)
                            ) {
                                if (tvHelper?.hostComputer != null && tvHelper?.managerBinder != null) {
                                    if (!showDialog) {
                                        TvNavigation(
                                            app = AppComputerObject(viewModel.app),
                                            computer = tvHelper?.hostComputer!!,
                                            managerBinder = tvHelper?.managerBinder!!
                                        )
                                    }
                                }
                            }
                        }
                    }

                    viewModel.setIsMainView(false)
                }

                if (viewModel.getTVScreenState()) {
                    when {
                        showDialog -> {
                            AlertDialog(
                                dialogTitle = title,
                                dialogText = message,
                                confirm = buttonLabel,
                                icon = icon,
                                isNeutralRequired = false,
                                isCancelRequired = isRequired,
                                onConfirmation = {
                                    if (isQuitting) {
                                        viewModel.setIsQuitting(false)
                                        viewModel.setTvAlertDialog(false)
                                        ServerHelper.isAppRunning = isAppRunning
                                        lifecycleScope.launch {
                                            ServerHelper.doQuit(
                                                parent = this@MainView,
                                                computer = tvHelper?.hostComputer!!,
                                                app = viewModel.app!!,
                                                binder = tvHelper?.managerBinder!!,
                                                onComplete = tvHelper?.quitAndStartJob()
                                            )
                                        }
                                    } else {
                                        viewModel.setTvAlertDialog(false)
                                        finish()
                                    }
                                },
                                onDismissRequest = {
                                    viewModel.setTvAlertDialog(false)
                                    if (isQuitting) {
                                        viewModel.setIsQuitting(false)
                                        finish()
                                    }
                                },
                                onNeutralRequest = {},
                                dismiss = getString(R.string.cancel),
                                neutral = "",
                                dismissOnBackPress = false
                            )
                        }
                    }

                    when {
                        showProgressLoader -> {
                            ProgressDialog(
                                title = stringResource(R.string.conn_establishing_msg),
                                message = stringResource(R.string.applist_connect_msg),
                                icon = R.drawable.baseline_private_connectivity_24,
                                onDismissRequest = { viewModel.setProgressDialog(false) })
                        }
                    }

                    LaunchedEffect(showProgressLoader) {
                        if (showProgressLoader) {
                            delay(3000.milliseconds)
                            viewModel.setProgressDialog(false)
                        }
                    }
                }

                updateFavoritesButtonVisibility()
            }
        }
    }

    // Helper function to handle data collection back press
    fun handleDataCollectionBackPress() {
        if (viewModel.canGoBack) {
            viewModel.getNavController()
                ?.popBackStack()
        } else {
            Toast.makeText(
                this@MainView,
                getString(R.string.please_wait_for_the_process_to_complete),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Helper function to handle add computer back press
    fun handleAddComputerBackPress() {
        viewModel.getNavController()?.popBackStack()
        viewModel.setItemSelected(0)
    }

    // Helper function to handle key left
    fun handleKeyLeft(isSideMenuOpen: Boolean) {
        if (!isSideMenuOpen) {
            viewModel.setIsSideMenuOpen(true)
        } else {
            return
        }
    }

    // Handle back press in details screen
    fun handleDetailsBackPress() {
        viewModel.getNavController()?.popBackStack()
        viewModel.setIsMenuRequired(true)
        viewModel.setDetailsScreenState(false)
        viewModel.setItemSelected(if (viewModel.isFavs) 1 else 0)
    }

    // Handle back press in trailer screen
    fun handleTrailerBackPress() {
        viewModel.getNavController()?.popBackStack()
        if (viewModel.isDetailsScreen.value == false) viewModel.setIsMenuRequired(true)
        else viewModel.setIsMenuRequired(false)
    }

    override fun onStop() {
        super.onStop()
        if (viewModel.getTVScreenState()) {
            viewModel.setProgressDialog(false)
            viewModel.setResumeSession(false)
            viewModel.setAppRunning(false)
            viewModel.setTVScreenState(false)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.setIsMainView(false)
        viewModel.setIsNotDeepLink(false)

        // Close database
        GameDataBaseManager.getInstance(this)?.close()

        // Close log
        if (viewModel.getIsStatsEnabled()) {
            lifecycleScope.launch(Dispatchers.IO) {
                delay(2000.milliseconds)
                LimeLog.writeLog("CLEANUP: Closing log")
                LimeLog.close()
            }
        }
        cancelQuitGameJob()
        info("QuitGameJob cancelled")
    }

    // Handle back press in settings screen
    fun handleMainSettingsBackPress() {
        if (viewModel.isMainView.value == true) {
            viewModel.setItemSelected(5)
        } else {
            viewModel.setScreenState("ComputerScreen")
            viewModel.setItemSelected(0)
        }
        viewModel.getNavController()!!.popBackStack()
    }

    // Handle back press in options screen
    fun handleOptionsBackPress() {
        viewModel.getNavController()?.popBackStack()
        if (viewModel.isMainView.value == true) {
            viewModel.setItemSelected(if (viewModel.isFavs) 1 else 0)
        } else {
            viewModel.setScreenState("ComputerScreen")
            viewModel.setItemSelected(0)
        }
    }

    // Handle back press in help screen
    fun handleHelpBackPress() {
        viewModel.getNavController()!!
            .popBackStack()
        if (viewModel.isMainView.value == true) {
            viewModel.setItemSelected(if (viewModel.isFavs) 1 else 0)
        } else {
            viewModel.setScreenState("ComputerScreen")
            viewModel.setItemSelected(0)
        }
    }

    // Helper functions to show/hide the favorites button
    override fun showFavoritesButton() {
        viewModel.setFavoritesVisibility(true)
    }

    // Helper functions to show/hide the favorites button
    override fun hideFavoritesButton() {
        viewModel.setFavoritesVisibility(false)
    }

    // Update favorites button visibility
    private fun updateFavoritesButtonVisibility() {
        if (viewModel.getIsLeanbackEnabled()) {
            viewModel.setFavoritesVisibility(viewModel.getHasFavorites())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        val screenState = viewModel.getScreenState()

        when (screenState) {
            "MainScreen" -> {
                viewModel.triggerReload()
            }

            "FavoritesScreen" -> {
                viewModel.triggerReload()
            }

            "AppViewScreen" -> {
                viewModel.triggerReload()
            }

            "ComputerScreen" -> {
                viewModel.triggerReload()
            }
        }

    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Save instance state
        outState.putString("NAME", viewModel.name)
        outState.putBoolean("PAIR", viewModel.pairState)
        outState.putString("UUID", viewModel.uuidString)
        outState.putBoolean("HIDDEN", viewModel.showHiddenApps)

        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)

        viewModel.name = savedInstanceState.getString("NAME") ?: ""
        viewModel.pairState = savedInstanceState.getBoolean("PAIR")
        viewModel.uuidString = savedInstanceState.getString("UUID") ?: ""
        viewModel.showHiddenApps = savedInstanceState.getBoolean("HIDDEN")
    }

    // Reload AppViewScreen
    override fun reloadAppViewScreen() {
        viewModel.reloaded.observe(this) { reloaded ->
            if (!reloaded) {
                viewModel.setReloaded(true)
            }
        }
    }

    // Load details screen
    override fun loadAppDetails(
        app: AppComputerObject,
        uuid: String,
        computer: ComputerDetails,
        managerBinder: ComputerManagerService.ComputerManagerBinder,
        favs: Boolean
    ) {
        val navController = viewModel.getNavController() ?: return
        navController.navigate(Screens.DetailsScreen.route) {
            launchSingleTop = true
        }

        viewModel.setDetailsScreenState(true)
        viewModel.setIsMenuRequired(false)
        viewModel.setSelectedApp(app)
        viewModel.uuidString = uuid
        viewModel.setComputerDetails(computer)
        viewModel.setBinder(managerBinder)
        viewModel.isFavs = favs
    }

    override fun reloadMainViewScreen(hidden: Boolean) {
        viewModel.showHiddenApps = hidden

        viewModel.triggerReload()

        viewModel.reloaded.observe(this) { reloaded ->
            if (!reloaded) {
                viewModel.setReloaded(true)
            }
        }
    }

    override fun reloadAppViewScreen(hidden: Boolean) {
        viewModel.showHiddenApps = hidden
        viewModel.triggerReload()

        viewModel.reloaded.observe(this) { reloaded ->
            if (!reloaded) {
                viewModel.setReloaded(true)
            }
        }
    }

    override fun onFavoritesCleared() {
        viewModel.setFavoritesVisibility(false)
    }

    override fun onSettingsLaunched() {
        viewModel.getNavController()?.navigate(Screens.MainSettingsScreen.route) {
            launchSingleTop = true
        }
    }

    override fun onHelpLaunched() {
        viewModel.url = SETUP_GUIDE_URL
        viewModel.getNavController()?.navigate(Screens.HelpScreen.route) {
            launchSingleTop = true
        }
    }

    override fun onExitLaunched() {
        viewModel.getNavController()?.navigate(Screens.Exit.route) {
            launchSingleTop = true
        }
    }

    override fun reloadMainViewScreen() {
        viewModel.reloaded.observe(this) { reloaded ->
            if (!reloaded) {
                viewModel.setReloaded(true)
            }
        }
    }

    override fun updateViewLoading(loading: Boolean) {
        viewModel.setAppLoaderValue(loading)
    }

    override fun onCreateContextMenu(
        menu: ContextMenu?,
        v: View?,
        menuInfo: ContextMenu.ContextMenuInfo?
    ) {
        super.onCreateContextMenu(menu, v, menuInfo)
        val screenState = viewModel.getScreenState()

        // Set the theme only for the menu as the app is composed
        setTheme(R.style.GamerTheme)

        // Set the header icon
        val icon = R.drawable.baseline_apps_24

        viewModel.selectedApp.observe(this) { selectedApp ->
            this.selectedApp = selectedApp
        }

        when (screenState) {
            "AppViewScreen" -> {
                appViewContextMenu(selectedApp, menu!!)
            }

            "MainScreen" -> {
                menu!!.setHeaderIcon(ResourcesCompat.getDrawable(resources, icon, null))
                mainScreenContextMenu(selectedApp, menu)
                menu.setHeaderTitle(selectedApp!!.app?.appName)
            }

            "FavoritesScreen" -> {
                favoritesContextMenu(selectedApp, menu!!)
            }

            "ComputerScreen" -> {
                computerContextMenu(menu)
            }
        }
    }

    // Helper function to handle computer context menu
    private fun computerContextMenu(
        menu: ContextMenu?
    ) {
        computer?.stopPolling(false)

        val computerDetails = viewModel.getSelectedPc()

        // Set the theme only for the menu as the app is composed
        setTheme(R.style.GamerTheme)

        menu!!.clearHeader()

        val headerIcon =
            if (computerDetails.state == ComputerDetails.State.OFFLINE) R.drawable.ic_pc_offline_gamer else R.drawable.ic_computer_small

        menu.setHeaderIcon(headerIcon)

        // Build header title dynamically
        val headerTitle = buildString {
            append(computerDetails.name)
            append(" - ")
            append(
                when (computerDetails.state) {
                    ComputerDetails.State.ONLINE -> resources.getString(R.string.pcview_menu_header_online)
                    ComputerDetails.State.OFFLINE -> resources.getString(R.string.pcview_menu_header_offline)
                    else -> resources.getString(R.string.pcview_menu_header_unknown)
                }
            )
        }
        menu.setHeaderTitle(headerTitle)

        // Inflate context menu items based on computer state
        when {
            computerDetails.state == ComputerDetails.State.OFFLINE || computerDetails.state == ComputerDetails.State.UNKNOWN -> {
                menu.add(Menu.NONE, WOL_ID, 1, resources.getString(R.string.pcview_menu_send_wol))
                menu.add(
                    Menu.NONE,
                    GAMESTREAM_EOL_ID,
                    2,
                    resources.getString(R.string.pcview_menu_eol)
                )
            }

            computerDetails.pairState != PairingManager.PairState.PAIRED -> {
                menu.add(Menu.NONE, PAIR_ID, 1, resources.getString(R.string.pcview_menu_pair_pc))
                if (computerDetails.nvidiaServer) {
                    menu.add(
                        Menu.NONE,
                        GAMESTREAM_EOL_ID,
                        2,
                        resources.getString(R.string.pcview_menu_eol)
                    )
                }
            }

            else -> {
                // Paired and not offline/unknown
                if (computerDetails.nvidiaServer) {
                    menu.add(
                        Menu.NONE,
                        GAMESTREAM_EOL_ID,
                        3,
                        resources.getString(R.string.pcview_menu_eol)
                    )
                }
                menu.add(
                    Menu.NONE,
                    FULL_APP_LIST_ID,
                    4,
                    resources.getString(R.string.pcview_menu_app_list)
                )
            }
        }

        // Add common menu items
        menu.add(
            Menu.NONE,
            TEST_NETWORK_ID,
            5,
            resources.getString(R.string.pcview_menu_test_network)
        )

        if (computerDetails.pairState == PairingManager.PairState.PAIRED) {
            menu.add(Menu.NONE, DELETE_ID, 6, resources.getString(R.string.pcview_menu_delete_pc))
        }

        menu.add(
            Menu.NONE,
            VIEW_COMPUTER_DETAILS_ID,
            7,
            resources.getString(R.string.pcview_menu_details)
        )
    }

    // Helper function for adding favorites context menu items
    private fun favoritesContextMenu(
        selectedApp: AppComputerObject?,
        menu: ContextMenu
    ) {
        if (favsHelper?.lastRunningAppId != 0) {
            if (favsHelper?.lastRunningAppId == selectedApp!!.app?.id) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, R.string.applist_menu_resume)
                menu.add(Menu.NONE, QUIT_ID, 2, R.string.applist_menu_quit)
            } else {
                menu.add(Menu.NONE, START_WITH_QUIT, 1, R.string.applist_menu_quit_and_start)
            }
        }

        if (favsHelper?.lastRunningAppId == 0) {
            menu.add(Menu.NONE, VIEW_DETAILS_ID, 3, R.string.applist_menu_details)
        }

        val isSunshine = viewModel.name == "Sunshine"
        var isFavorite = false
        if (isSunshine) {
            for ((_, id) in sunFavAppIds) {
                if (selectedApp!!.app?.id == id) {
                    isFavorite = true
                }
            }
        } else {
            for ((_, id) in favAppIds) {
                if (selectedApp!!.app?.id == id) {
                    isFavorite = true
                }
            }
        }

        // Add the favorite item
        if (favsHelper?.lastRunningAppId != selectedApp!!.app?.id) {
            val favoriteItem = menu.add(Menu.NONE, ADD_FAVORITE_ID, 4, R.string.favorites)
            favoriteItem.isCheckable = true
            favoriteItem.isChecked = isFavorite
        }
    }

    // Helper function for adding main context menu items
    private fun mainScreenContextMenu(
        selectedApp: AppComputerObject?,
        menu: ContextMenu
    ) {
        if (homeViewHelper?.lastRunningAppId != 0) {
            if (homeViewHelper?.lastRunningAppId == selectedApp!!.app?.id) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, R.string.applist_menu_resume)
                menu.add(Menu.NONE, QUIT_ID, 2, R.string.applist_menu_quit)
            } else {
                menu.add(Menu.NONE, START_WITH_QUIT, 1, R.string.applist_menu_quit_and_start)
            }
        }

        if (homeViewHelper?.lastRunningAppId == 0) {
            menu.add(Menu.NONE, VIEW_DETAILS_ID, 3, R.string.applist_menu_details)
        }

        menu.add(Menu.NONE, FULL_APP_LIST_ID, 4, R.string.pcview_menu_app_list)

        val shouldShowHideApp =
            homeViewHelper?.lastRunningAppId != selectedApp!!.app?.id || homeViewHelper?.hiddenAppIds!!.contains(
                selectedApp.app?.id
            )
        val isSunshine = viewModel.name == "Sunshine"
        var isFavorite = false
        if (isSunshine) {
            for ((_, id) in sunFavAppIds) {
                if (selectedApp.app?.id == id) {
                    isFavorite = true
                }
            }
        } else {
            for ((_, id) in favAppIds) {
                if (selectedApp.app?.id == id) {
                    isFavorite = true
                }
            }
        }

        if (shouldShowHideApp && (!isSunshine || !isFavorite)) {
            val hideAppItem = menu.add(
                Menu.NONE,
                HIDE_APP_ID,
                5,
                R.string.applist_menu_hide_app
            )
            hideAppItem.isCheckable = true
            hideAppItem.isChecked =
                homeViewHelper?.hiddenAppIds!!.contains(selectedApp.app?.id)
        }

        // Add the favorite item
        if (homeViewHelper?.lastRunningAppId != selectedApp.app?.id) {
            val favoriteItem = menu.add(
                Menu.NONE,
                ADD_FAVORITE_ID,
                6,
                R.string.favorites
            )
            favoriteItem.isCheckable = true
            favoriteItem.isChecked = isFavorite
        }
    }

    // Helper function for adding app grid context menu items
    private fun appViewContextMenu(
        selectedApp: AppComputerObject?,
        menu: ContextMenu
    ) {
        if (appViewHelper?.lastRunningAppId != 0) {
            if (appViewHelper?.lastRunningAppId == selectedApp!!.app?.id) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, R.string.applist_menu_resume)
                menu.add(Menu.NONE, QUIT_ID, 2, R.string.applist_menu_quit)
            } else {
                menu.add(Menu.NONE, START_WITH_QUIT, 1, R.string.applist_menu_quit_and_start)
            }
        }

        if (appViewHelper?.lastRunningAppId == 0) {
            menu.add(Menu.NONE, VIEW_DETAILS_ID, 3, R.string.applist_menu_details)
        }

        menu.add(Menu.NONE, FULL_APP_LIST_ID, 4, R.string.pcview_menu_app_list)

        val shouldShowHideApp =
            appViewHelper?.lastRunningAppId != selectedApp!!.app?.id || appViewHelper?.hiddenAppIds!!.contains(
                selectedApp.app?.id!!
            )
        if (shouldShowHideApp) {
            menu.add(Menu.NONE, HIDE_APP_ID, 5, R.string.applist_menu_hide_app)
                .setCheckable(true).isChecked = appViewHelper?.hiddenAppIds!!.contains(
                selectedApp.app?.id!!
            )
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {

        viewModel.selectedApp.observe(this) { selectedApp ->
            this.selectedApp = selectedApp
        }

        val screenState = viewModel.getScreenState()

        return when (screenState) {
            "AppViewScreen" -> {
                appViewSelectedContextMenu(item)
            }

            "MainScreen" -> {
                mainScreenSelectedContextMenu(item)
            }

            "FavoritesScreen" -> {
                favoritesSelectedContextMenu(item)
            }

            "ComputerScreen" -> {
                computerSelectedContextMenu(item)
            }

            else -> false
        }
    }

    // Helper function for selected computer context menu items
    private fun computerSelectedContextMenu(item: MenuItem): Boolean {
        val selectedPc = viewModel.getSelectedPc()

        // Use a map to store computer details
        val computerDetails = mapOf(
            "Name" to selectedPc.name,
            "Status" to selectedPc.state,
            "Paired Status" to selectedPc.pairState,
            "Active Address" to selectedPc.activeAddress,
            "IPv6 Address" to selectedPc.ipv6Address,
            "Remote Address" to selectedPc.remoteAddress,
            "Manual Address" to selectedPc.manualAddress,
            "MAC Address" to selectedPc.macAddress,
            "HTTPS Port" to if (selectedPc.httpsPort != 0) selectedPc.httpsPort.toString()
            else null
        )

        // Build the details string
        val data = computerDetails.filterValues { it != null }
            .map { "${it.key}: ${it.value}" }
            .joinToString("\n")

        return when (item.itemId) {
            PAIR_ID -> {
                selectedPc.let { computer?.doPair(it) }
                true
            }

            WOL_ID -> {
                selectedPc.let { computer?.doWakeOnLan(it) }
                true
            }

            DELETE_ID -> {

                if (ActivityManager.isUserAMonkey()) {
                    info("Ignoring delete PC request from monkey")
                }
                viewModel.setDialogImage(R.drawable.baseline_delete_forever_24)
                viewModel.setDialogMessage(resources.getString(R.string.delete_pc_msg))
                viewModel.setDialogTitle(selectedPc.name.toString())
                viewModel.setConfirmButtonLabel(
                    resources.getString(R.string.yes)
                )
                viewModel.setCancelButtonLabel(
                    resources.getString(R.string.cancel)
                )
                viewModel.setIsDelete(true)
                viewModel.setIsPairing(false)
                viewModel.setIsCrash(false)
                viewModel.setIsHelp(false)
                viewModel.setDialogValue(true)
                viewModel.setDismissOnBack(true)
                viewModel.setIsCancelRequired(true)
                selectedPc.let { viewModel.setSelectedPc(it) }
                true
            }

            FULL_APP_LIST_ID -> {
                lifecycleScope.launch {
                    selectedPc.let {
                        computer?.doAppList(
                            it,
                            newlyPaired = false,
                            showHiddenGames = true
                        )
                    }
                }
                true
            }

            VIEW_COMPUTER_DETAILS_ID -> {

                viewModel.setDialogImage(R.drawable.ic_computer_small)
                viewModel.setDialogTitle(resources.getString(R.string.title_details))
                viewModel.setDialogMessage(data)
                viewModel.setConfirmButtonLabel(
                    resources.getString(android.R.string.ok)
                )
                viewModel.setCancelButtonLabel(
                    resources.getString(R.string.help)
                )
                viewModel.setIsDelete(false)
                viewModel.setIsPairing(false)
                viewModel.setIsCrash(false)
                viewModel.setIsHelp(true)
                viewModel.setDialogValue(true)
                viewModel.setDismissOnBack(false)
                viewModel.setIsCancelRequired(true)
                true
            }

            TEST_NETWORK_ID -> {
                viewModel.setDoNetTest(true)
                true
            }

            GAMESTREAM_EOL_ID -> {
                viewModel.url = GAME_STREAM_EOL_FAQ_URL
                viewModel.getNavController()
                    ?.navigate(Screens.HelpScreen.route) {
                        launchSingleTop = true
                    }
                true
            }

            else -> super.onContextItemSelected(item)
        }
    }

    // Helper function for selected favorites context menu items
    private fun favoritesSelectedContextMenu(item: MenuItem): Boolean {
        return when (item.itemId) {
            START_WITH_QUIT -> {
                viewModel.setSelectedApp(selectedApp!!)
                viewModel.setComputerDetails(viewModel.computer!!)
                viewModel.setBinder(favsHelper?.managerBinder!!)
                viewModel.setDialogValue(true)
                viewModel.setDialogMessage(getString(R.string.applist_quit_confirmation))
                viewModel.setDialogTitle(getString(R.string.confirm_quit))
                viewModel.setDialogImage(R.drawable.baseline_stop_circle_24)
                viewModel.setCancelButtonLabel(getString(R.string.cancel))
                viewModel.setIsCancelRequired(true)
                viewModel.setIsNeutralRequired(false)
                viewModel.setConfirmButtonLabel(getString(R.string.quit))
                viewModel.setIsQuitting(false)
                viewModel.setStartWithQuit(true)
                true
            }

            START_OR_RESUME_ID -> {
                selectedApp!!.app?.let {
                    favsHelper?.managerBinder?.let { binder ->
                        ServerHelper.doStartStream(
                            this as ComponentActivity,
                            it,
                            viewModel.computer!!,
                            binder,
                            viewModel
                        )
                    }
                }
                true
            }

            QUIT_ID -> {
                favsHelper?.suspendGridUpdates = true
                viewModel.setSelectedApp(selectedApp!!)
                viewModel.setComputerDetails(viewModel.computer!!)
                viewModel.setBinder(favsHelper?.managerBinder!!)
                viewModel.setDialogValue(true)
                viewModel.setDialogMessage(getString(R.string.applist_quit_confirmation))
                viewModel.setDialogTitle(getString(R.string.confirm_quit))
                viewModel.setDialogImage(R.drawable.baseline_stop_circle_24)
                viewModel.setCancelButtonLabel(getString(R.string.cancel))
                viewModel.setIsCancelRequired(true)
                viewModel.setIsNeutralRequired(false)
                viewModel.setConfirmButtonLabel(getString(R.string.quit))
                viewModel.setIsQuitting(true)
                viewModel.setStartWithQuit(false)
                true
            }

            VIEW_DETAILS_ID -> {
                favsHelper?.showDetails(
                    selectedApp,
                    favsHelper?.managerBinder,
                    viewModel.computer,
                    viewModel.uuidString
                )
                true
            }

            ADD_FAVORITE_ID -> {
                lifecycleScope.launch(Dispatchers.Main) {
                    favsHelper?.handleToggleFavorite(item.isChecked, selectedApp)
                }
                true
            }

            else -> super.onContextItemSelected(item)
        }
    }

    // Helper function for selected main context menu items
    private fun mainScreenSelectedContextMenu(item: MenuItem): Boolean {
        return when (item.itemId) {
            START_WITH_QUIT -> {
                viewModel.setSelectedApp(selectedApp!!)
                viewModel.setComputerDetails(viewModel.computer!!)
                viewModel.setBinder(homeViewHelper?.managerBinder!!)
                viewModel.setDialogValue(true)
                viewModel.setDialogMessage(getString(R.string.applist_quit_confirmation))
                viewModel.setDialogTitle(getString(R.string.confirm_quit))
                viewModel.setDialogImage(R.drawable.baseline_stop_circle_24)
                viewModel.setCancelButtonLabel(getString(R.string.cancel))
                viewModel.setIsCancelRequired(true)
                viewModel.setIsNeutralRequired(false)
                viewModel.setConfirmButtonLabel(getString(R.string.quit))
                viewModel.setIsQuitting(false)
                viewModel.setStartWithQuit(true)
                true
            }

            START_OR_RESUME_ID -> {
                selectedApp!!.app?.let {
                    homeViewHelper?.managerBinder?.let { binder ->
                        ServerHelper.doStartStream(
                            this as ComponentActivity,
                            it,
                            viewModel.computer!!,
                            binder,
                            viewModel
                        )
                    }
                }
                true
            }

            QUIT_ID -> {
                homeViewHelper?.suspendGridUpdates = true
                viewModel.setSelectedApp(selectedApp!!)
                viewModel.setComputerDetails(viewModel.computer!!)
                viewModel.setBinder(homeViewHelper?.managerBinder!!)
                viewModel.setDialogValue(true)
                viewModel.setDialogMessage(getString(R.string.applist_quit_confirmation))
                viewModel.setDialogTitle(getString(R.string.confirm_quit))
                viewModel.setDialogImage(R.drawable.baseline_stop_circle_24)
                viewModel.setCancelButtonLabel(getString(R.string.cancel))
                viewModel.setIsCancelRequired(true)
                viewModel.setIsNeutralRequired(false)
                viewModel.setConfirmButtonLabel(getString(R.string.quit))
                viewModel.setIsQuitting(true)
                viewModel.setStartWithQuit(false)
                true
            }

            FULL_APP_LIST_ID -> {
                homeViewHelper?.refreshAppList()
                true
            }

            VIEW_DETAILS_ID -> {
                homeViewHelper?.showDetails(
                    selectedApp,
                    homeViewHelper?.managerBinder,
                    viewModel.computer,
                    viewModel.computer?.uuid
                )
                true
            }

            HIDE_APP_ID -> {
                lifecycleScope.launch {
                    homeViewHelper?.handleHideApp(selectedApp!!, item.isChecked)
                }
                true
            }

            ADD_FAVORITE_ID -> {
                lifecycleScope.launch(Dispatchers.Main) {
                    homeViewHelper?.handleToggleFavorite(item.isChecked, selectedApp)
                }
                true
            }

            else -> super.onContextItemSelected(item)
        }
    }

    // Helper function for selected app grid context menu items
    private fun appViewSelectedContextMenu(item: MenuItem): Boolean {
        return when (item.itemId) {
            START_WITH_QUIT -> {
                viewModel.setSelectedApp(selectedApp!!)
                viewModel.setDialogValue(true)
                viewModel.setDialogMessage(getString(R.string.applist_quit_confirmation))
                viewModel.setDialogTitle(getString(R.string.confirm_quit))
                viewModel.setDialogImage(R.drawable.baseline_stop_circle_24)
                viewModel.setCancelButtonLabel(getString(R.string.cancel))
                viewModel.setIsCancelRequired(true)
                viewModel.setIsNeutralRequired(false)
                viewModel.setConfirmButtonLabel(getString(R.string.quit))
                viewModel.setIsQuitting(false)
                viewModel.setStartWithQuit(true)
                true
            }

            START_OR_RESUME_ID -> {
                selectedApp!!.app?.let {
                    appViewHelper?.handleStartOrResume(selectedApp!!)
                }
                true
            }

            QUIT_ID -> {
                appViewHelper?.suspendGridUpdates = true
                viewModel.setSelectedApp(selectedApp!!)
                viewModel.setDialogValue(true)
                viewModel.setDialogMessage(getString(R.string.applist_quit_confirmation))
                viewModel.setDialogTitle(getString(R.string.confirm_quit))
                viewModel.setDialogImage(R.drawable.baseline_stop_circle_24)
                viewModel.setCancelButtonLabel(getString(R.string.cancel))
                viewModel.setIsCancelRequired(true)
                viewModel.setIsNeutralRequired(false)
                viewModel.setConfirmButtonLabel(getString(R.string.quit))
                viewModel.setIsQuitting(true)
                viewModel.setStartWithQuit(false)
                true
            }

            FULL_APP_LIST_ID -> {
                appViewHelper?.refreshAppList()
                true
            }

            VIEW_DETAILS_ID -> {
                appViewHelper?.showDetails(
                    selectedApp,
                    viewModel.managerBinder,
                    viewModel.computer,
                    viewModel.computer?.uuid
                )
                true
            }

            HIDE_APP_ID -> {
                lifecycleScope.launch {
                    appViewHelper?.handleHideApp(selectedApp!!, item.isChecked)
                }
                true
            }

            else -> super.onContextItemSelected(item)
        }
    }

    // Helper function to check if the database exists
    private fun doesDatabaseExist(context: Context): Boolean {
        val dbFile: File = context.getDatabasePath(GameDataBaseManager.DB_NAME)
        return dbFile.exists()
    }

    private fun cancelQuitGameJob() {
        quitGameJob?.cancel()
    }

    override fun popStack() {
        viewModel.getNavController()?.popBackStack()
        viewModel.setItemSelected(4)
        computer?.startComputerUpdates()
    }

    override fun updateNavBar() {
        viewModel.setIsMenuRequired(false)
    }

    override fun startDataCollection(uuid: String) {
        viewModel.uuidString = uuid
        viewModel.getNavController()?.navigate(Screens.DataCollectionScreen.route) {
            launchSingleTop = true
        }
    }

    companion object {
        var dataBaseManager: GameDataBaseManager? = null
            private set
    }

}