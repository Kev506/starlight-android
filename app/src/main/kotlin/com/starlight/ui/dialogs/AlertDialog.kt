package com.starlight.ui.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    onConfirmation: () -> Unit,
    onNeutralRequest: () -> Unit,
    dialogTitle: String,
    dialogText: String,
    confirm: String,
    dismiss: String,
    neutral: String,
    isNeutralRequired: Boolean = false,
    isCancelRequired: Boolean = false,
    isPositiveRequired: Boolean = true,
    icon: Int,
    dismissOnBackPress: Boolean,
) {
    ClassicAlertDialog(
        containerColor = Color(0xFF026D91),
        tonalElevation = 8.dp,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        properties = DialogProperties(
            usePlatformDefaultWidth = true,
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = false
        ),
        icon = {
            Image(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(50.dp)
            )
        },
        title = {
            Text(text = dialogTitle, color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Text(text = dialogText, color = Color.White)
        },
        onDismissRequest = {
            onDismissRequest()
        },
        isNeutralRequired = isNeutralRequired,
        positiveButton = {
            if (isPositiveRequired) {
                Button(
                    modifier = Modifier.padding(start = 10.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = Color(0x3433B5E5),
                        focusedContainerColor = Color(0xff33b5e5),
                        pressedContainerColor = Color(0x3433B5E5),
                        focusedContentColor = Color.White,
                        pressedContentColor = Color.White
                    ),
                    onClick = {
                        onConfirmation()
                    }
                ) {
                    Text(confirm, color = Color.White)
                }
            }
        },
        negativeButton = {
            if (isCancelRequired) {
                Button(
                    modifier = Modifier.padding(start = if (isNeutralRequired) 10.dp else 0.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = Color(0x3433B5E5),
                        focusedContainerColor = Color(0xff33b5e5),
                        pressedContainerColor = Color(0x3433B5E5),
                        focusedContentColor = Color.White,
                        pressedContentColor = Color.White
                    ),
                    onClick = {
                        onDismissRequest()
                    }
                ) {
                    Text(dismiss, color = Color.White)
                }
            }
        },
        neutralButton = {
            if (isNeutralRequired) {
                Button(
                    colors = ButtonDefaults.colors(
                        containerColor = Color(0x3433B5E5),
                        focusedContainerColor = Color(0xff33b5e5),
                        pressedContainerColor = Color(0x3433B5E5),
                        focusedContentColor = Color.White,
                        pressedContentColor = Color.White
                    ),
                    onClick = {
                        onNeutralRequest()
                    }
                ) {
                    Text(neutral, color = Color.White)
                }
            }
        }
    )
}