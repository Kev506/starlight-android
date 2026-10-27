package com.starlight.ui.composables

import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.tv.material3.DrawerValue.Closed
import androidx.tv.material3.DrawerValue.Open
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import com.starlight.R
import com.starlight.models.MainViewModel
import com.starlight.navigation.NavGroup
import com.starlight.navigation.listOfNavItems
import com.starlight.ui.modifiers.handleMainMenuBackEvent
import com.starlight.ui.modifiers.handleOnKeyRight
import com.starlight.utils.Constants.SETUP_GUIDE_URL
import kotlinx.coroutines.yield

@SuppressLint("RestrictedApi")
@Composable
fun AppDrawer(
    viewModel: MainViewModel,
    mainContentRequester: FocusRequester,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val activity = LocalActivity.current as ComponentActivity
    val selectedItem by viewModel.menuItemSelected.observeAsState(0)
    val drawerState = rememberDrawerState(initialValue = Closed)
    val isSideMenuOpen by viewModel.isSideMenuOpen.observeAsState(false)
    val isFavoritesVisible by viewModel.isFavoritesVisible.observeAsState(false)
    val isMainView by viewModel.isMainView.observeAsState(false)
    val isMenuRequired by viewModel.isMenuRequired.observeAsState(true)
    val isBackInProgress by viewModel.isBackInProgress.observeAsState(false)
    val coroutineScope = rememberCoroutineScope()
    var focusedIndex by remember { mutableIntStateOf(-1) }

    val isFireOS = remember {
        Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
    }

    val allVisibleItems = remember(isMainView, isFireOS, isFavoritesVisible, listOfNavItems) {
        val menuSetupItems = if (!isMainView) {
            listOfNavItems.filter { it.group == NavGroup.SYSTEM }
        } else {
            listOfNavItems.filter { it.group == NavGroup.APPS }
        }

        val visibleItems = if (isFireOS) {
            menuSetupItems.filterNot { it.title == R.string.help }
        } else {
            menuSetupItems
        }

        if (!isFavoritesVisible) {
            visibleItems.filterNot { it.title == R.string.favorites }
        } else {
            visibleItems
        }
    }

    val selectedItemIndex = allVisibleItems.indexOfFirst { it.id == selectedItem }

    val drawerFocusRequesters = remember(allVisibleItems.size) {
        Array(allVisibleItems.size) { FocusRequester() }
    }

    LaunchedEffect(isSideMenuOpen) {
        if (isSideMenuOpen) {
            drawerState.setValue(Open)
            yield()
            drawerFocusRequesters.getOrNull(selectedItemIndex)?.requestFocus()
        } else {
            drawerState.setValue(Closed)
            yield()
            mainContentRequester.requestFocus()
        }
    }

    val navBackStackEntry by viewModel.getNavController()?.currentBackStackEntryAsState() ?: remember { mutableStateOf(null) }

    LaunchedEffect(navBackStackEntry) {
        val backQueueCount = viewModel.getNavController()?.currentBackStack?.value?.size ?: 0

        if (backQueueCount > 2) {
            viewModel.setBackInProgress(true)
        } else {
            viewModel.setBackInProgress(false)
        }
    }

    ModalNavigationDrawer(
        drawerContent = { _ ->
            AppHeaderImage(
                isSideMenuOpen = isSideMenuOpen,
                viewModel = viewModel,
                isMainView = isMainView,
                isMenuRequired = isMenuRequired
            )
            Spacer(modifier = modifier.height(10.dp))

            if (isMenuRequired) {
                Column(
                    modifier = modifier
                        .handleOnKeyRight(
                            viewModel = viewModel,
                            parent = activity,
                            mainContentRequester = mainContentRequester
                        )
                        .handleMainMenuBackEvent(
                            viewModel = viewModel,
                            parent = activity,
                            isSideMenuOpen = isSideMenuOpen,
                            coroutineScope = coroutineScope
                        )
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .padding(12.dp)
                        .focusProperties {
                            onExit = { mainContentRequester.requestFocus() }
                            canFocus = !isBackInProgress || isMainView || isSideMenuOpen
                        }
                        .background(Color.Transparent)
                        .selectableGroup()
                        .focusGroup()
                        .focusRestorer(drawerFocusRequesters.getOrElse(selectedItemIndex) { FocusRequester.Default })
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.Start
                ) {
                    Spacer(modifier = modifier.weight(1f))
                    allVisibleItems.forEachIndexed { index, navItem ->

                        val isCurrentlyFocused = (focusedIndex == index)

                        NavigationDrawerItem(
                            modifier = modifier
                                .onFocusChanged {
                                    viewModel.setIsSideMenuOpen(it.hasFocus)

                                    focusedIndex = if (it.hasFocus) {
                                        index
                                    } else {
                                        -1
                                    }
                                }
                                .focusRequester(drawerFocusRequesters[index]),
                            selected = isCurrentlyFocused,
                            onClick = {
                                navItem.route?.let {
                                    viewModel.getNavController()?.navigate(it) {
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                                when (navItem.title) {
                                    R.string.help -> {
                                        viewModel.url = SETUP_GUIDE_URL
                                        viewModel.setIsMenuRequired(true)
                                    }

                                    R.string.settings -> {
                                        viewModel.setOptionsScreenState(true)
                                    }
                                }

                                viewModel.setItemSelected(navItem.id)
                                viewModel.setIsSideMenuOpen(false)
                                mainContentRequester.requestFocus()
                            },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(id = navItem.selectedIcon),
                                    contentDescription = null,
                                    modifier = modifier
                                        .size(24.dp)
                                )
                            },
                            glow = NavigationDrawerItemDefaults.glow(
                                glow = Glow.None
                            ),
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = Color.Transparent
                            )
                        ) {
                            AnimatedVisibility(visible = isSideMenuOpen) {
                                Text(
                                    text = stringResource(navItem.title),
                                    fontSize = 16.sp,
                                    color = if (isCurrentlyFocused) Color.Black else Color.White
                                )
                            }
                        }
                        if (index == allVisibleItems.size - 2) {
                            Spacer(modifier = modifier.weight(1f))
                        }
                    }
                }
            }
        },
        modifier = modifier
            .background(Color.Transparent),
        drawerState = drawerState,
        scrimBrush = Brush.horizontalGradient(
            colors = listOf(
                Color.Black.copy(alpha = 0.9f),
                Color.Transparent
            ),
            startX = 0f,
            endX = 3000f
        )
    ) {
        content()
    }
}

@Composable
fun AppHeaderImage(
    isSideMenuOpen: Boolean,
    viewModel: MainViewModel,
    isMainView: Boolean,
    isMenuRequired: Boolean
) {
    if (isMenuRequired) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .padding(start = 30.dp, end = 20.dp, top = 0.dp, bottom = 12.dp)
                .focusProperties { canFocus = false },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_computer_small),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.padding(5.dp))

            AnimatedVisibility(visible = isSideMenuOpen) {
                Text(
                    text = buildString {
                        append(stringResource(R.string.pc2))
                        append(" ")
                        if (isMainView) {
                            append(viewModel.name)
                        }
                    },
                    textAlign = TextAlign.Center,
                    fontSize = 16.sp,
                    color = Color.White
                )
            }
        }
    }
}
