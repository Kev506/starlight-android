package com.starlight.ui.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.NavigationDrawerItemBorder
import androidx.tv.material3.NavigationDrawerItemColors
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.NavigationDrawerItemGlow
import androidx.tv.material3.NavigationDrawerItemScale
import androidx.tv.material3.NavigationDrawerItemShape
import androidx.tv.material3.NavigationDrawerScope

/**
 * Created by Kevin Andrews on 04/09/2026.
 */

@Composable
fun NavigationDrawerScope.NavigationDrawerItem(
    selected: Boolean,
    onClick: () -> Unit,
    leadingContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    tonalElevation: Dp = NavigationDrawerItemDefaults.NavigationDrawerItemElevation,
    shape: NavigationDrawerItemShape = NavigationDrawerItemDefaults.shape(),
    colors: NavigationDrawerItemColors = NavigationDrawerItemDefaults.colors(),
    scale: NavigationDrawerItemScale = NavigationDrawerItemScale.None,
    border: NavigationDrawerItemBorder = NavigationDrawerItemDefaults.border(),
    glow: NavigationDrawerItemGlow = NavigationDrawerItemDefaults.glow(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {

    // Set the custom width of the navigation drawer item
    val collapsedDrawerItemWidth = 56.dp
    val expandedDrawerItemWidth = 200.dp

    val animatedWidth by
    animateDpAsState(
        targetValue =
            if (hasFocus) {
                expandedDrawerItemWidth
            } else {
                collapsedDrawerItemWidth
            },
        label = "NavigationDrawerItem width open/closed state of the drawer item",
    )
    val navDrawerItemHeight =
        if (supportingContent == null) {
            NavigationDrawerItemDefaults.ContainerHeightOneLine
        } else {
            NavigationDrawerItemDefaults.ContainerHeightTwoLine
        }
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = {
            AnimatedVisibility(
                visible = hasFocus,
                enter = NavigationDrawerItemDefaults.ContentAnimationEnter,
                exit = NavigationDrawerItemDefaults.ContentAnimationExit,
            ) {
                content()
            }
        },
        leadingContent = {
            Box(
                Modifier
                    .size(NavigationDrawerItemDefaults.IconSize)
            ) { leadingContent() }
        },
        trailingContent =
            trailingContent?.let {
                {
                    AnimatedVisibility(
                        visible = hasFocus,
                        enter = NavigationDrawerItemDefaults.ContentAnimationEnter,
                        exit = NavigationDrawerItemDefaults.ContentAnimationExit,
                    ) {
                        it()
                    }
                }
            },
        supportingContent =
            supportingContent?.let {
                {
                    AnimatedVisibility(
                        visible = hasFocus,
                        enter = NavigationDrawerItemDefaults.ContentAnimationEnter,
                        exit = NavigationDrawerItemDefaults.ContentAnimationExit,
                    ) {
                        it()
                    }
                }
            },
        modifier =
            modifier.layout { measurable, constraints ->
                val width = animatedWidth.roundToPx()
                val height = navDrawerItemHeight.roundToPx()
                val placeable =
                    measurable.measure(
                        constraints.copy(
                            minWidth = width,
                            maxWidth = width,
                            minHeight = height,
                            maxHeight = height,
                        )
                    )
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            },
        enabled = enabled,
        onLongClick = onLongClick,
        tonalElevation = tonalElevation,
        shape = shape.toToggleableListItemShape(),
        colors = colors.toToggleableListItemColors(hasFocus),
        scale = scale.toToggleableListItemScale(),
        border = border.toToggleableListItemBorder(),
        glow = glow.toToggleableListItemGlow(),
        interactionSource = interactionSource,
    )
}

@Composable
private fun NavigationDrawerItemShape.toToggleableListItemShape() =
    ListItemDefaults.shape(
        shape = shape,
        focusedShape = focusedShape,
        pressedShape = pressedShape,
        selectedShape = selectedShape,
        disabledShape = disabledShape,
        focusedSelectedShape = focusedSelectedShape,
        focusedDisabledShape = focusedDisabledShape,
        pressedSelectedShape = pressedSelectedShape,
    )

@Composable
private fun NavigationDrawerItemColors.toToggleableListItemColors(
    doesNavigationDrawerHaveFocus: Boolean
) =
    ListItemDefaults.colors(
        containerColor = containerColor,
        contentColor = if (doesNavigationDrawerHaveFocus) contentColor else inactiveContentColor,
        focusedContainerColor = focusedContainerColor,
        focusedContentColor = focusedContentColor,
        pressedContainerColor = pressedContainerColor,
        pressedContentColor = pressedContentColor,
        selectedContainerColor = selectedContainerColor,
        selectedContentColor = selectedContentColor,
        disabledContainerColor = disabledContainerColor,
        disabledContentColor =
            if (doesNavigationDrawerHaveFocus) disabledContentColor
            else disabledInactiveContentColor,
        focusedSelectedContainerColor = focusedSelectedContainerColor,
        focusedSelectedContentColor = focusedSelectedContentColor,
        pressedSelectedContainerColor = pressedSelectedContainerColor,
        pressedSelectedContentColor = pressedSelectedContentColor,
    )

@Composable
private fun NavigationDrawerItemScale.toToggleableListItemScale() =
    ListItemDefaults.scale(
        scale = scale,
        focusedScale = focusedScale,
        pressedScale = pressedScale,
        selectedScale = selectedScale,
        disabledScale = disabledScale,
        focusedSelectedScale = focusedSelectedScale,
        focusedDisabledScale = focusedDisabledScale,
        pressedSelectedScale = pressedSelectedScale,
    )

@Composable
private fun NavigationDrawerItemBorder.toToggleableListItemBorder() =
    ListItemDefaults.border(
        border = border,
        focusedBorder = focusedBorder,
        pressedBorder = pressedBorder,
        selectedBorder = selectedBorder,
        disabledBorder = disabledBorder,
        focusedSelectedBorder = focusedSelectedBorder,
        focusedDisabledBorder = focusedDisabledBorder,
        pressedSelectedBorder = pressedSelectedBorder,
    )

@Composable
private fun NavigationDrawerItemGlow.toToggleableListItemGlow() =
    ListItemDefaults.glow(
        glow = glow,
        focusedGlow = focusedGlow,
        pressedGlow = pressedGlow,
        selectedGlow = selectedGlow,
        focusedSelectedGlow = focusedSelectedGlow,
        pressedSelectedGlow = pressedSelectedGlow,
    )