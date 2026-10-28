package com.starlight.ui.screens

import android.app.Service
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import com.starlight.R
import com.starlight.computers.ComputerManagerService
import com.starlight.models.MainViewModel
import com.starlight.navigation.Screens
import com.starlight.ui.dialogs.AlertDialog
import com.starlight.ui.dialogs.ProgressDialog
import com.starlight.ui.helpers.AddComputerHelper
import com.starlight.ui.modifiers.handleKeyEvent
import com.starlight.utils.Constants.TROUBLESHOOTING_URL
import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.warning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun AddComputerScreen(
    viewModel: MainViewModel
) {
    val activity = LocalActivity.current as ComponentActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val resources = LocalResources.current

    val composeData = remember {
        AddComputerHelper(
            uiContext = activity,
            resources = resources,
            viewModel = viewModel
        )
    }
    composeData.uiContext = activity

    DisposableEffect(lifecycleOwner) {
        val bound = activity.bindService(
            Intent(
                activity,
                ComputerManagerService::class.java
            ), composeData.serviceConnection, Service.BIND_AUTO_CREATE
        )
        onDispose {
            if (bound) {
                try {
                    activity.unbindService(composeData.serviceConnection)
                    if (composeData.addComputerJob != null) {
                        composeData.addComputerJob?.cancel()
                        info("addComputerJob Cancelled")
                    }
                } catch (e: IllegalArgumentException) {
                    warning("Error unbinding service connect $e")
                }
            }
        }
    }

    AddComputerScreenContent(
        viewModel = viewModel,
        composeData = composeData
    )
}

@Composable
fun AddComputerScreenContent(
    viewModel: MainViewModel,
    composeData: AddComputerHelper
) {

    val initialFocusRequester = remember { FocusRequester() }
    val img = viewModel.image.observeAsState()
    val dialogText = viewModel.dialogMessage.observeAsState("")
    val openAlertDialog = viewModel.openAlertDialog.observeAsState(false)
    val showLoader = viewModel.showLoaderState.observeAsState(false)
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(showLoader.value) {
        if (showLoader.value) {
            delay(2000.milliseconds)
            viewModel.setLoaderValue(false)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = Color.Transparent)
    ) {
        ConstraintLayout(
            modifier = Modifier
                .fillMaxSize()
                .handleKeyEvent(
                    viewModel = viewModel,
                    parent = LocalActivity.current as ComponentActivity,
                    isSideMenuOpen = false
                )
                .onFocusChanged {
                    if (it.hasFocus) {
                        viewModel.setBackInProgress(false)
                    }
                }
                .focusable(true)
        ) {

            val (hostTextView, manuallyAddPcText, background) = createRefs()

            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(R.drawable.portrait_mode)
                    .crossfade(false)
                    .allowHardware(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .constrainAs(background) {
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                        start.linkTo(parent.start)
                        end.linkTo(parent.end)
                    }
            )

            val text = remember { mutableStateOf("") }
            val inputHandler = LocalSoftwareKeyboardController.current

            val guidelineVerticalRef = createGuidelineFromStart(0.90f)

            Text(
                text = stringResource(R.string.title_add_pc),
                fontSize = 24.sp,
                color = White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .constrainAs(manuallyAddPcText) {
                        top.linkTo(parent.top, margin = 15.dp)
                        start.linkTo(parent.start, margin = 30.dp)
                        end.linkTo(parent.end, margin = 10.dp)
                    }
            )

            Row(
                modifier = Modifier
                    .constrainAs(hostTextView) {
                        start.linkTo(parent.start, margin = 30.dp)
                        top.linkTo(manuallyAddPcText.bottom, margin = 25.dp)
                        end.linkTo(guidelineVerticalRef, margin = 10.dp)
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = text.value,
                    textStyle = TextStyle(color = White),
                    onValueChange = {
                        text.value = it
                    },
                    singleLine = true,
                    maxLines = 1,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF33B5E5),
                        unfocusedBorderColor = Color(0x6D0099CC)
                    ),
                    leadingIcon = {
                        Image(
                            painter = painterResource(id = R.drawable.baseline_computer_24),
                            contentDescription = null
                        )
                    },
                    placeholder = {
                        Text(
                            stringResource(R.string.ip_hint),
                            color = White,
                            fontSize = 16.sp
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                           coroutineScope.launch {
                                composeData.handleDoneEvent(
                                    text.value
                                )
                            }
                            inputHandler?.hide()
                        }
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .background(Color.Transparent)
                        .onKeyEvent { event ->
                            if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                               coroutineScope.launch {
                                    composeData.handleDoneEvent(
                                        text.value
                                    )
                                }
                                inputHandler?.hide()
                                true
                            } else {
                                false
                            }
                        }
                        .focusRequester(initialFocusRequester)
                        .focusable()
                )
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {
                       coroutineScope.launch {
                            composeData.handleDoneEvent(
                                text.value
                            )
                        }
                        inputHandler?.hide()
                    },
                    scale = ButtonDefaults.scale(focusedScale = 1f),
                    colors = ButtonDefaults.colors(
                        containerColor = Color(0x6D0099CC),
                        disabledContainerColor = Color(0xFFBDBDBD),
                        focusedContainerColor = Color(0xFF33B5E5),
                        pressedContainerColor = Color(0xC9020122),
                        contentColor = White
                    ),
                    modifier = Modifier
                        .width(150.dp)
                        .height(50.dp)
                        .onPreviewKeyEvent {
                            when (it.key) {
                                Key.DirectionLeft if it.type == KeyEventType.KeyDown -> {
                                    initialFocusRequester.requestFocus()
                                    false
                                }

                                else -> {
                                    false
                                }
                            }
                        },
                    content = {
                        Text(
                            stringResource(id = android.R.string.ok).uppercase(),
                            fontSize = 14.sp,
                            color = White,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .weight(1f)
                                .align(Alignment.CenterVertically)
                                .padding(top = 5.dp)
                        )
                    }
                )
            }

            LaunchedEffect(Unit) {
                initialFocusRequester.requestFocus()
            }

            when {
                openAlertDialog.value -> {
                    dialogText.value.let {
                        img.value?.let { it1 ->
                            AlertDialog(
                                onDismissRequest = {
                                    viewModel.url = TROUBLESHOOTING_URL
                                    viewModel.getNavController()?.navigate(Screens.HelpScreen.route)
                                    viewModel.setDialogValue(false)
                                },
                                onConfirmation = {
                                    viewModel.setDialogValue(false)
                                },
                                dialogTitle = stringResource(R.string.conn_error_title),
                                dialogText = it,
                                icon = it1,
                                confirm = stringResource(android.R.string.ok),
                                dismiss = stringResource(R.string.help),
                                neutral = "",
                                isNeutralRequired = false,
                                isCancelRequired = true,
                                onNeutralRequest = { /* Nothing to do here */ },
                                dismissOnBackPress = true
                            )
                        }
                    }
                }
            }
            when {
                showLoader.value -> {
                    ProgressDialog(
                        title = stringResource(R.string.title_add_pc),
                        message = stringResource(R.string.msg_add_pc),
                        icon = R.drawable.baseline_add_to_queue_24,
                        onDismissRequest = { viewModel.setLoaderValue(false) }
                    )
                }
            }

        }
    }
}