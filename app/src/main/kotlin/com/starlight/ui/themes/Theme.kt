package com.starlight.ui.themes

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.starlight.ui.helpers.UiHelper.setActivityGameModeState
import com.starlight.ui.helpers.UiHelper.setLocale


@Composable
fun StarlightTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        setLocale(context)
        setActivityGameModeState(context as ComponentActivity)
    }

    val gamerColorScheme = darkColorScheme(
        primary = HoloBright,
        onPrimary = White,
        secondary = HoloTint,
        onSecondary = White,
        tertiary = TransBlue,
        background = Black,
        surface = GamerSurface,
        onBackground = White,
        onSurface = White,
        error = Color.Red,
        onError = White
    )

    MaterialTheme(
        colorScheme = gamerColorScheme,
        typography = Typography,
        content = content
    )
}
