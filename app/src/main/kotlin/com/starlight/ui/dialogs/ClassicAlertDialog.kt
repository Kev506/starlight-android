package com.starlight.ui.dialogs

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
fun ClassicAlertDialog(
    onDismissRequest: () -> Unit,
    positiveButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    negativeButton: @Composable (() -> Unit)? = null,
    neutralButton: @Composable (() -> Unit)? = null,
    isNeutralRequired: Boolean = false,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties()
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            // To show 3 buttons we need to put them into one button slot because the official
            // version stopped supporting 3 buttons for some reason.
            AlertDialogButtonStack(centerButtons = isNeutralRequired) {
                positiveButton.invoke()
                negativeButton?.invoke()
                // If we don't have the negative button we want to place an empty view here to
                // trigger the logic of pushing neutral button to the side because there are "3 buttons".
                if (negativeButton == null && neutralButton != null) {
                    Spacer(Modifier.size(0.dp))
                }
                neutralButton?.invoke()
            }
        },
        modifier = modifier,
        // We put all buttons inside the confirm button slot.
        dismissButton = null,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties
    )
}

/**
 * Custom layout for stacking buttons as per the material design spec.
 * If all buttons fit on one line. Place them like that.
 * When they don't fit, put them vertically below each other.
 *
 * We need to implement this manually because the compose material implementation does not follow these guidelines and places buttons like [androidx.compose.foundation.layout.FlowRow].
 */
@Composable
private fun AlertDialogButtonStack(
    modifier: Modifier = Modifier,
    centerButtons: Boolean = false,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = content
    ) { measurables, constraints ->
        // Don't constrain child views further, measure them with given constraints.
        // List of measured children
        val placedButtons = measurables.map { measurable ->
            // Measure each child
            measurable.measure(constraints)
        }

        // Calculate amount of required space for the buttons.
        val widthOfButtons = placedButtons.sumOf { placeable ->
            placeable.width
        }

        if (widthOfButtons < constraints.maxWidth) {
            // Calculate height of the whole button row.
            val maxHeight = placedButtons.maxOfOrNull { placeable ->
                placeable.height
            } ?: 0

            // When all buttons fit horizontally, place them like that.
            // Place buttons either centrally aligned or at the end of the layout.
            layout(constraints.maxWidth, maxHeight) {
                // Track the current X coordinate for placing children.
                var xPosition = when {
                    centerButtons -> {
                        (constraints.maxWidth + widthOfButtons) / 2
                    }
                    else -> {
                        constraints.maxWidth
                    }
                }

                // Place children in the parent layout.
                placedButtons.forEachIndexed { _, placeable ->
                    placeable.placeRelative(
                        x = xPosition - placeable.width,
                        y = 0
                    )

                    // Move the X coordinate by the currently placed button.
                    xPosition -= placeable.width
                }
            }
        } else {
            val heightOfButtons = placedButtons.sumOf { placeable ->
                placeable.height
            }

            // When all buttons don't fit. Place them vertically.
            layout(constraints.maxWidth, heightOfButtons) {
                // Track the Y coordinate we have placed children up to.
                var yPosition = 0

                // Place children in the parent layout.
                placedButtons.forEach { placeable ->
                    // Position item on the screen.
                    placeable.placeRelative(
                        x = if (centerButtons) (constraints.maxWidth - placeable.width) / 2 else constraints.maxWidth - placeable.width,
                        y = yPosition
                    )

                    // Move the Y coordinate by the currently placed button.
                    yPosition += placeable.height
                }
            }
        }
    }
}
