package com.starlight.ui.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.RadioButton
import androidx.tv.material3.RadioButtonDefaults
import androidx.tv.material3.Text

/**
 * Created by Kevin Andrews on 28/02/2026.
 */

@Composable
fun ButtonListAlertDialog(
    entryNames: List<String>,
    entryValues: List<String>,
    initialSelection: String?,
    onDismissRequest: () -> Unit,
    onNeutralRequest: () -> Unit,
    onClick: (String) -> Unit,
    dialogTitle: String,
    confirm: String,
    dismiss: String,
    neutral: String,
    isNeutralRequired: Boolean = false,
    isCancelRequired: Boolean = false,
    isPositiveRequired: Boolean = false,
    icon: Int
) {
    var selectedOption by remember { mutableStateOf(initialSelection) }
    val options = remember(entryNames, entryValues) { entryNames.zip(entryValues) }

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        val index = options.indexOfFirst { it.second == selectedOption }
        if (index >= 0) {
            listState.scrollToItem(index = index)
            focusRequester.requestFocus()
        }
    }

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
        onDismissRequest = onDismissRequest,
        icon = {
            Image(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(50.dp)
            )
        },
        title = { Text(text = dialogTitle, color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(state = listState, modifier = Modifier.selectableGroup()) {
                items(options) { (name, value) ->
                    val isSelected = (value == selectedOption)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .then(if (isSelected) Modifier.focusRequester(focusRequester) else Modifier)
                            .selectable(
                                selected = isSelected,
                                onClick = { onClick(value) },
                                role = Role.RadioButton
                            )
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Color(0xff33b5e5),
                                unselectedColor = Color.White
                            )
                        )
                        Text(
                            text = name,
                            modifier = Modifier.padding(start = 16.dp),
                            color = Color.White
                        )
                    }
                }
            }
        },
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
                    onClick = { /* Nothing to do here */ }
                ) {
                    Text(confirm, color = Color.White)
                }
            }
        },
        negativeButton = {
            if (isCancelRequired) {
                Button(
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
