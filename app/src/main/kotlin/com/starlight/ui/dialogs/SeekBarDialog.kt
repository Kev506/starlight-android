package com.starlight.ui.dialogs

import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Created by Kevin Andrews on 03/03/2026.
 */

@Composable
fun SeekBarDialog(
    currentPrefValue: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    confirm: String,
    dismiss: String,
    icon: Int,
    dialogTitle: String,
) {
    var pendingValue by remember { mutableFloatStateOf(currentPrefValue.toFloat()) }

    val min = 500f
    val max = 350000f
    val dPadStep = 1000f
    val visualStep = 500f

    val internalSteps = ((max - min) / visualStep).toInt() - 1

    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }

    ClassicAlertDialog(
        containerColor = Color(0xFF026D91),
        tonalElevation = 8.dp,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        properties = DialogProperties(
            usePlatformDefaultWidth = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        ),
        onDismissRequest = onDismiss,
        icon = {
            Image(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(50.dp)
            )
        },
        title = { Text(text = dialogTitle, color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${pendingValue / 1000.0} Mbps",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Slider(
                    value = pendingValue,
                    onValueChange = { pendingValue = it },
                    valueRange = min..max,
                    steps = internalSteps,
                    colors = SliderDefaults.colors(
                        thumbColor = if (isFocused) Color(0xFF97D3EC).copy(alpha = 0.5f) else Color.LightGray.copy(
                            alpha = 0.5f
                        ),
                        activeTrackColor = if (isFocused) Color.White.copy(alpha = 0.5f) else Color.LightGray.copy(
                            alpha = 0.5f
                        ),
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        activeTickColor = Color.White.copy(alpha = 0.5f),
                        inactiveTickColor = Color.White.copy(alpha = 0.1f)
                    ),
                    modifier = Modifier
                        .onKeyEvent { event ->
                            event.type == KeyEventType.KeyDown && when (event.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    pendingValue = (pendingValue - dPadStep).coerceAtLeast(min)
                                    true
                                }

                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    pendingValue = (pendingValue + dPadStep).coerceAtMost(max)
                                    true
                                }

                                else -> false
                            }
                        }
                        .focusRequester(focusRequester)
                        .onFocusChanged { isFocused = it.isFocused }
                        .focusable()
                )
            }
        },
        positiveButton = {
            Button(
                modifier = Modifier.padding(start = 10.dp),
                colors = ButtonDefaults.colors(
                    containerColor = Color(0x3433B5E5),
                    focusedContainerColor = Color(0xff33b5e5),
                    pressedContainerColor = Color(0x3433B5E5),
                    focusedContentColor = Color.White,
                    pressedContentColor = Color.White
                ),
                onClick = { onConfirm(pendingValue.toInt()) }
            ) {
                Text(confirm, color = Color.White)
            }
        },
        negativeButton = {
            Button(
                colors = ButtonDefaults.colors(
                    containerColor = Color(0x3433B5E5),
                    focusedContainerColor = Color(0xff33b5e5),
                    pressedContainerColor = Color(0x3433B5E5),
                    focusedContentColor = Color.White,
                    pressedContentColor = Color.White
                ),
                onClick = {
                    onDismiss()
                }
            ) {
                Text(dismiss, color = Color.White)
            }
        }
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
