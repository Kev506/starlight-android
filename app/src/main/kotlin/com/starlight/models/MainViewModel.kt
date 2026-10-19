package com.starlight.models

import android.os.Bundle
import androidx.compose.runtime.mutableStateListOf
import androidx.core.os.BundleCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.navigation.NavHostController
import com.starlight.computers.ComputerManagerService.ComputerManagerBinder
import com.starlight.navigation.Screens
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.NvApp
import com.starlight.ui.helpers.AppViewHelper
import com.starlight.ui.helpers.ComputerHelper
import com.starlight.ui.helpers.DataCollectionHelper
import com.starlight.ui.helpers.FavoritesHelper
import com.starlight.ui.helpers.HomeScreenHelper
import com.starlight.ui.helpers.OptionsHelper.TvItem
import com.starlight.ui.helpers.TvHomeHelper
import com.starlight.utils.AppComputerObject
import com.starlight.utils.Constants.APPID
import com.starlight.utils.Constants.APPNAME
import com.starlight.utils.Constants.CAN_GO_BACK
import com.starlight.utils.Constants.COMPUTER_CHANGED
import com.starlight.utils.Constants.DATABASE_CLEARED
import com.starlight.utils.Constants.IS_FAVS
import com.starlight.utils.Constants.IS_HDR_SUPPORTED
import com.starlight.utils.Constants.NAME
import com.starlight.utils.Constants.PAIR_STATE
import com.starlight.utils.Constants.PCNAME
import com.starlight.utils.Constants.SHOW_HIDDEN_APPS
import com.starlight.utils.Constants.UNIQUEID
import com.starlight.utils.Constants.URL
import com.starlight.utils.Constants.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// Helper class for managing state and live data in the ViewModel
class MainViewModel(private val state: SavedStateHandle) : ViewModel() {

    var sessionStartTime: Long = 0L

    // Start tv stream session timer
    fun startSessionTimer() {
        sessionStartTime = System.currentTimeMillis()
    }

    // Get elapsed tv stream session time
    fun getElapsedSessionTimeSeconds(): Long {
        if (sessionStartTime == 0L) return 0L
        return (System.currentTimeMillis() - sessionStartTime) / 1000
    }

    // Clear tv stream session timer
    fun clearSessionTimer() {
        sessionStartTime = 0L
    }

    // MutableLiveData for tracking is menu required
    val isMenuRequired: LiveData<Boolean>
        field = MutableLiveData(true)

    // MutableLiveData for tracking is computer screen
    val isComputerScreen: LiveData<Boolean>
        field = MutableLiveData(true)

    // MutableLiveData for tracking is deep link
    val isNotDeepLink: LiveData<Boolean>
        field = MutableLiveData(false)

    // MutableLiveData for tracking has session resumed
    val resumeSession: LiveData<Boolean>
        field = MutableLiveData(false)

    // MutableLiveData for tracking is back in progress
    val isBackInProgress: LiveData<Boolean>
        field = MutableLiveData(false)

    // MutableLiveData for tracking selected app
    val selectedApp: LiveData<AppComputerObject?>
        field = MutableLiveData<AppComputerObject?>()

    // MutableLiveData for setting video id
    val videoId: LiveData<String>
        field = MutableLiveData<String>()

    // MutableLiveData for tracking computer manager binder
    val binder: LiveData<ComputerManagerBinder>
        field = MutableLiveData<ComputerManagerBinder>()

    // MutableLiveData for tracking computer details
    val getComputer: LiveData<ComputerDetails>
        field = MutableLiveData<ComputerDetails>()

    // MutableLiveData for tracking app view helper
    val appViewHelper: LiveData<AppViewHelper>
        field = MutableLiveData<AppViewHelper>()

    // MutableLiveData for tracking home screen helper
    val homeViewHelper: LiveData<HomeScreenHelper>
        field = MutableLiveData<HomeScreenHelper>()

    // MutableLiveData for tracking favorites helper
    val favsHelper: LiveData<FavoritesHelper>
        field = MutableLiveData<FavoritesHelper>()

    // MutableLiveData for tracking data collection helper
    val dataCollectionHelper: LiveData<DataCollectionHelper>
        field = MutableLiveData<DataCollectionHelper>()

    // MutableLiveData for tracking computer helper
    val computerHelper: LiveData<ComputerHelper>
        field = MutableLiveData<ComputerHelper>()

    // MutableLiveData for tracking tv screen helper
    val tvHelper: LiveData<TvHomeHelper>
        field = MutableLiveData<TvHomeHelper>()

    // MutableLiveData for tracking start with quit
    val startWithQuit: LiveData<Boolean>
        field = MutableLiveData(false)

    // MutableStateFlow for tracking pc state
    val noPcFoundState: LiveData<Boolean>
        field = MutableLiveData(true)

    // MutableLiveData for tracking refresh id
    val refreshId: LiveData<Int>
        field = MutableLiveData(0)

    // MutableStateFlow for tracking crash count
    val crashCountFlow: LiveData<Int>
        field = MutableLiveData(0)

    // MutableStateFlow for tracking crash state
    val crashStateFlow: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for language data
    val languageChanged: LiveData<Boolean>
        field = MutableLiveData<Boolean>()

    // LiveData for reloaded data
    val reloaded: LiveData<Boolean>
        field = MutableLiveData<Boolean>()

    // LiveData for app running state
    val isAppRunning: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for app stopped state
    val isStopped: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for details screen state
    val isDetailsScreen: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for options screen state
    val isOptionsScreen: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for screen state
    val screen: LiveData<String>
        field = MutableLiveData<String>()

    // LiveData for TV screen state
    val isTVScreen: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for navigation controller
    val navController: LiveData<NavHostController>
        field = MutableLiveData<NavHostController>()

    // LiveData for alert dialog state
    val openAlertDialog: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for tv alert dialog state
    val tvOpenAlertDialog: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for loader state
    val showLoaderState: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for app loader state
    val showAppLoaderState: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for computer loader state
    val showComputerLoaderState: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for paired state
    val isPaired: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for offline state
    val isOffline: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for dialog message
    val dialogMessage: LiveData<String>
        field = MutableLiveData("")

    // LiveData for dialog title
    val dialogTitle: LiveData<String>
        field = MutableLiveData("")

    // LiveData for button text
    val cancelButtonText: LiveData<String>
        field = MutableLiveData("")

    // LiveData for confirm button text
    val confirmButtonText: LiveData<String>
        field = MutableLiveData("")

    // LiveData for neutral button text
    val neutralButtonText: LiveData<String>
        field = MutableLiveData("")

    // LiveData for is cancel required
    val isCancelRequired: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for is neutral required
    val isNeutralRequired: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for dialog image
    val image: LiveData<Int>
        field = MutableLiveData<Int>()

    // LiveData for download progress
    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress = _downloadProgress.asStateFlow()

    // LiveData for quitting Dialog
    val isQuitting: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for show progress dialog
    val showProgressDialog: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for progress message
    val progressMessage: LiveData<String>
        field = MutableLiveData("")

    // LiveData for error state
    val isErrorState: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for perf overlay
    val perfOverlay: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for perf overlay text
    val perfOverlayText: LiveData<String>
        field = MutableLiveData("")

    // LiveData for stats required
    val statsRequired: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for notification overlay
    val notificationOverlay: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for notification overlay text
    val notificationOverlayText: LiveData<String>
        field = MutableLiveData("")

    // LiveData for dismiss on back
    val dismissOnBack: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for doNetTest
    val doNetTest: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for isHelp
    val isHelp: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for isCrash
    val isCrash: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for isDelete
    val isDelete: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for isPairing
    val isPairing: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for selected PC
    val selectedPc: LiveData<ComputerDetails?>
        field = MutableLiveData<ComputerDetails?>()

    // LiveData for last running appId
    val lastRunningAppId: LiveData<Int>
        field = MutableLiveData(0)

    // LiveData for side menu state
    val isSideMenuOpen: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for selected menu item
    val menuItemSelected: LiveData<Int>
        field = MutableLiveData(0)

    // LiveData for is main view
    val isMainView: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for is moving
    val isGridMoving: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for is favorites visible
    val isFavoritesVisible: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for drawer can focus
    val currentRoute: LiveData<String>
        field = MutableLiveData(Screens.MainScreen.route)

    // LiveData for leanback enabled
    val isLeanbackEnabled: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for stats enabled
    val isStatsEnabled: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for has favorites
    val hasFavorites: LiveData<Boolean>
        field = MutableLiveData(false)

    // LiveData for has database
    val hasDataBase: LiveData<Boolean>
        field = MutableLiveData(false)

    // Items list for the OptionsScreen
    val items = mutableStateListOf<TvItem>()

    // Videos list for the VideoScreen
    val videos = mutableListOf<DbVideoItem>()

    // Create a bundle for parcelable or serializable objects
    var bundle = Bundle()

    // Save string & boolean values
    var uniqueIdString: String? = null
        set(value) {
            field = value
            state[UNIQUEID] = value
        }
    var pcName: String? = null
        set(value) {
            field = value
            state[PCNAME] = value
        }
    var appName: String? = null
        set(value) {
            field = value
            state[APPNAME] = value
        }
    var appId: String? = null
        set(value) {
            field = value
            state[APPID] = value
        }
    var uuidString: String? = null
        set(value) {
            field = value
            state[UUID] = value
        }
    var name: String? = null
        set(value) {
            field = value
            state[NAME] = value
        }
    var showHiddenApps: Boolean = false
        set(value) {
            field = value
            state[SHOW_HIDDEN_APPS.toString()] = value
        }
    var pairState: Boolean = false
        set(value) {
            field = value
            state[PAIR_STATE.toString()] = value
        }
    var url: String? = null
        set(value) {
            field = value
            state[URL] = value
        }
    var isHdrSupported: Boolean = false
        set(value) {
            field = value
            state[IS_HDR_SUPPORTED.toString()] = value
        }
    var computerChanged: Boolean = false
        set(value) {
            field = value
            state[COMPUTER_CHANGED.toString()] = value
        }
    var isFavs: Boolean = false
        set(value) {
            field = value
            state[IS_FAVS.toString()] = value
        }
    var canGoBack: Boolean = false
        set(value) {
            field = value
            state[CAN_GO_BACK.toString()] = value
        }
    var isDatabaseCleared: Boolean = false
        set(value) {
            field = value
            state[DATABASE_CLEARED.toString()] = value
        }

    var app: NvApp? = null
        set(value) {
            field = value
            bundle.putParcelable("app", value)
            state["appBundle"] = bundle
        }
    var computer: ComputerDetails? = null
        set(value) {
            field = value
            bundle.putParcelable("computer", value)
            state["computerBundle"] = bundle
        }
    var managerBinder: ComputerManagerBinder? = null
        set(value) {
            field = value
            bundle.putBinder("binder", value)
            state["binderBundle"] = bundle
        }
    var cert: ByteArray? = null
        set(value) {
            field = value
            bundle.putByteArray("cert", value)
            state["certBundle"] = bundle
        }

    // Initialize view model

    init {

        // Get string & boolean values
        uuidString = state.get<String>(UUID)
        appId = state.get<String>(APPID)
        name = state.get<String>(NAME)
        appName = state.get<String>(APPNAME)
        pcName = state.get<String>(PCNAME)
        uniqueIdString = state.get<String>(UNIQUEID)
        showHiddenApps = state.get<Boolean>(SHOW_HIDDEN_APPS.toString()) == true
        pairState = state.get<Boolean>(PAIR_STATE.toString()) == true
        isHdrSupported = state.get<Boolean>(IS_HDR_SUPPORTED.toString()) == true
        computerChanged = state.get<Boolean>(COMPUTER_CHANGED.toString()) == true
        isFavs = state.get<Boolean>(IS_FAVS.toString()) == true
        canGoBack = state.get<Boolean>(CAN_GO_BACK.toString()) == true
        isDatabaseCleared = state.get<Boolean>(DATABASE_CLEARED.toString()) == true
        url = state.get<String>(URL)

        // Get Parcelable, ByteArray, Integer or Serializable objects
        val savedBundle2 = state.get<Bundle>("computerBundle")
        computer = savedBundle2?.let {
            BundleCompat.getParcelable(
                it,
                "computer",
                ComputerDetails::class.java
            )
        }
        val savedBundle3 = state.get<Bundle>("binderBundle")
        managerBinder = savedBundle3?.let {
            it.getBinder("binder") as ComputerManagerBinder?
        }
        val savedBundle5 = state.get<Bundle>("certBundle")
        cert = savedBundle5?.let {
            it.getByteArray(
                it.getString("cert"),
            )
        }
        val savedBundle7 = state.get<Bundle>("appBundle")
        app = savedBundle7?.let {
            BundleCompat.getParcelable(
                it,
                "app",
                NvApp::class.java
            )
        }
    }

    fun setLanguageChanged(languageChanged: Boolean) {
        this.languageChanged.value = languageChanged
    }

    fun getLanguageChanged(): Boolean {
        return languageChanged.value ?: false
    }

    fun setReloaded(reloaded: Boolean) {
        this.reloaded.value = reloaded
    }

    fun setAppRunning(isAppRunning: Boolean) {
        this.isAppRunning.value = isAppRunning
    }

    fun getIsStopped(): Boolean {
        return isStopped.value ?: false
    }

    fun setIsStopped(isStopped: Boolean) {
        this.isStopped.value = isStopped
    }

    fun setDetailsScreenState(isDetailsScreen: Boolean) {
        this.isDetailsScreen.value = isDetailsScreen
    }

    fun setOptionsScreenState(isOptionsScreen: Boolean) {
        this.isOptionsScreen.value = isOptionsScreen
    }

    fun getScreenState(): String {
        return screen.value ?: ""
    }

    fun setScreenState(screen: String) {
        this.screen.value = screen
    }

    fun setTVScreenState(isTVScreen: Boolean) {
        this.isTVScreen.value = isTVScreen
    }

    fun getTVScreenState(): Boolean {
        return isTVScreen.value ?: false
    }

    fun setNavController(controller: NavHostController) {
        navController.value = controller
    }

    fun getNavController(): NavHostController? {
        return navController.value
    }

    fun updateNoPcFound(noPcFound: Boolean) {
        noPcFoundState.value = noPcFound
    }

    fun updateCrashState(crashState: Boolean) {
        crashStateFlow.value = crashState
    }

    fun updateCrashCount(crashCount: Int) {
        crashCountFlow.value = crashCount
    }

    fun triggerReload() {
        refreshId.value = (refreshId.value ?: 0) + 1
    }

    fun setDialogValue(value: Boolean) {
        openAlertDialog.value = value
    }

    fun setDialogMessage(text: String) {
        dialogMessage.value = text
    }

    fun setDialogTitle(text: String) {
        dialogTitle.value = text
    }

    fun setCancelButtonLabel(text: String) {
        cancelButtonText.value = text
    }

    fun setConfirmButtonLabel(text: String) {
        confirmButtonText.value = text
    }

    fun setNeutralButtonLabel(text: String) {
        neutralButtonText.value = text
    }

    fun setIsCancelRequired(value: Boolean) {
        isCancelRequired.value = value
    }

    fun setIsNeutralRequired(value: Boolean) {
        isNeutralRequired.value = value
    }

    fun setDialogImage(image: Int) {
        this.image.value = image
    }

    fun setLoaderValue(value: Boolean) {
        showLoaderState.value = value
    }

    fun setComputerLoaderValue(value: Boolean) {
        showComputerLoaderState.value = value
    }

    fun updateDownloadProgress(progress: Float) {
        _downloadProgress.value = progress
    }

    fun setIsQuitting(value: Boolean) {
        isQuitting.value = value
    }

    fun setProgressDialog(value: Boolean) {
        showProgressDialog.value = value
    }

    fun setProgressMessage(value: String) {
        progressMessage.value = value
    }

    fun setIsError(value: Boolean) {
        isErrorState.value = value
    }

    fun setPerfOverlay(value: Boolean) {
        perfOverlay.value = value
    }

    fun setPerfText(value: String) {
        perfOverlayText.value = value
    }

    fun setNotificationOverlay(value: Boolean) {
        notificationOverlay.value = value
    }

    fun setNotificationText(value: String) {
        notificationOverlayText.value = value
    }

    fun setDismissOnBack(value: Boolean) {
        dismissOnBack.value = value
    }

    fun setSelectedApp(app: AppComputerObject?) {
        selectedApp.value = app
    }

    fun setBinder(binder: ComputerManagerBinder) {
        this.binder.value = binder
    }

    fun setComputerDetails(computer: ComputerDetails) {
        getComputer.value = computer
    }

    fun setStartWithQuit(value: Boolean) {
        startWithQuit.value = value
    }

    fun setDoNetTest(value: Boolean) {
        doNetTest.value = value
    }

    fun setIsHelp(value: Boolean) {
        isHelp.value = value
    }

    fun setIsCrash(value: Boolean) {
        isCrash.value = value
    }

    fun setIsDelete(value: Boolean) {
        isDelete.value = value
    }

    fun setIsPairing(value: Boolean) {
        isPairing.value = value
    }

    fun setSelectedPc(computer: ComputerDetails?) {
        selectedPc.value = computer
    }

    fun getSelectedPc(): ComputerDetails {
        return selectedPc.value ?: ComputerDetails()
    }

    fun setLastRunningAppId(appId: Int) {
        lastRunningAppId.value = appId
    }

    fun setIsSideMenuOpen(value: Boolean) {
        isSideMenuOpen.value = value
    }

    fun setComputerHelper(value: ComputerHelper) {
        computerHelper.value = value
    }

    fun setTvHelper(value: TvHomeHelper) {
        tvHelper.value = value
    }

    fun setItemSelected(value: Int) {
        menuItemSelected.value = value
    }

    fun setDataCollectionHelper(value: DataCollectionHelper) {
        dataCollectionHelper.value = value
    }

    fun setIsMainView(value: Boolean) {
        isMainView.value = value
    }

    fun setIsGridMoving(moving: Boolean) {
        isGridMoving.value = moving
    }

    fun setAppViewHelper(appViewHelper: AppViewHelper) {
        this.appViewHelper.value = appViewHelper
    }

    fun setHomeViewHelper(homeViewHelper: HomeScreenHelper) {
        this.homeViewHelper.value = homeViewHelper
    }

    fun setFavsHelper(favsHelper: FavoritesHelper) {
        this.favsHelper.value = favsHelper
    }

    fun setFavoritesVisibility(visible: Boolean) {
        isFavoritesVisible.value = visible
    }

    fun setRoute(route: String) {
        currentRoute.value = route
    }

    fun setIsPaired(value: Boolean) {
        isPaired.value = value
    }

    fun setIsOffline(value: Boolean) {
        isOffline.value = value
    }

    fun setIsLeanbackEnabled(value: Boolean) {
        isLeanbackEnabled.value = value
    }

    fun setHasFavorites(value: Boolean) {
        hasFavorites.value = value
    }

    fun getHasFavorites(): Boolean {
        return hasFavorites.value ?: false
    }

    fun getIsLeanbackEnabled(): Boolean {
        return isLeanbackEnabled.value ?: false
    }

    fun setIsStatsEnabled(value: Boolean) {
        isStatsEnabled.value = value
    }

    fun getIsStatsEnabled(): Boolean {
        return isStatsEnabled.value ?: false
    }

    fun setHasDataBase(value: Boolean) {
        hasDataBase.value = value
    }

    fun setStatsRequired(value: Boolean) {
        statsRequired.value = value
    }

    fun getStatsRequired(): Boolean {
        return statsRequired.value ?: false
    }

    fun setVideoId(value: String?) {
        videoId.value = value ?: ""
    }

    fun setIsMenuRequired(value: Boolean) {
        isMenuRequired.value = value
    }

    fun setBackInProgress(value: Boolean) {
        isBackInProgress.value = value
    }

    fun setAppLoaderValue(value: Boolean) {
        showAppLoaderState.value = value
    }

    fun setResumeSession(value: Boolean) {
        resumeSession.value = value
    }

    fun getResumedSession(): Boolean {
        return resumeSession.value ?: false
    }

    fun setIsNotDeepLink(value: Boolean) {
        isNotDeepLink.value = value
    }

    fun setTvAlertDialog(value: Boolean) {
        tvOpenAlertDialog.value = value
    }

    fun setIsComputerScreen(value: Boolean) {
        isComputerScreen.value = value
    }

    fun getIsComputerScreen(): Boolean {
        return isComputerScreen.value ?: false
    }

}