package com.starlight.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.starlight.R

/**
 * Created by Kevin Andrews on 22/01/2026.
 */

enum class Screens(val route: String) {
    HomeScreen("home"),
    FavoritesScreen("favorites"),
    MainScreen("main"),
    AppViewScreen("app view"),
    MainSettingsScreen("main settings"),
    DataCollectionScreen("data collection"),
    SettingsScreen("settings"),
    AddPcScreen("add pc"),
    HelpScreen("help"),
    DetailsScreen("details"),
    TrailerPlayerScreen("trailer"),
    VideoScreen("videos"),
    GameStreamingScreen("game streaming"),
    Exit("exit")
}

enum class NavGroup {
    SYSTEM,
    APPS
}

data class NavItems(
    val id: Int,
    @param:StringRes val title: Int,
    val route: String?,
    @param:DrawableRes val selectedIcon: Int,
    val group: NavGroup
)

val listOfNavItems = listOf(
    NavItems(
        id = 0,
        title = R.string.title_add_pc,
        route = Screens.AddPcScreen.route,
        selectedIcon = R.drawable.baseline_add_circle_24,
        group = NavGroup.SYSTEM
    ),
    NavItems(
        id = 1,
        title = R.string.help,
        route = Screens.HelpScreen.route,
        selectedIcon = R.drawable.baseline_help_24,
        group = NavGroup.SYSTEM
    ),
    NavItems(
        id = 2,
        title = R.string.exit,
        route = Screens.Exit.route,
        selectedIcon = R.drawable.baseline_exit_to_app_24,
        group = NavGroup.SYSTEM
    ),
    NavItems(
        id = 3,
        title = R.string.settings,
        route = Screens.SettingsScreen.route,
        selectedIcon = R.drawable.baseline_settings_24,
        group = NavGroup.SYSTEM
    ),
    NavItems(
        id = 0,
        title = R.string.games,
        route = null,
        selectedIcon = R.drawable.ic_baseline_sports_esports_24,
        group = NavGroup.APPS
    ),
    NavItems(
        id = 1,
        title = R.string.favorites,
        route = Screens.FavoritesScreen.route,
        selectedIcon = R.drawable.baseline_favorite_24,
        group = NavGroup.APPS
    ),
    NavItems(
        id = 2,
        title = R.string.help,
        route = Screens.HelpScreen.route,
        selectedIcon = R.drawable.baseline_help_24,
        group = NavGroup.APPS
    ),
    NavItems(
        id = 3,
        title = R.string.trailers,
        route = Screens.VideoScreen.route,
        selectedIcon = R.drawable.outline_video_library_24,
        group = NavGroup.APPS
    ),
    NavItems(
        id = 4,
        title = R.string.exit,
        route = Screens.Exit.route,
        selectedIcon = R.drawable.baseline_exit_to_app_24,
        group = NavGroup.APPS
    ),
    NavItems(
        id = 5,
        title = R.string.settings,
        route = Screens.SettingsScreen.route,
        selectedIcon = R.drawable.baseline_settings_24,
        group = NavGroup.APPS
    )
)

