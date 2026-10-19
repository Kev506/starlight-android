package com.starlight.ui.screens

import android.annotation.SuppressLint
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.starlight.models.MainViewModel
import com.starlight.navigation.Screens

/**
 * Created by Kevin Andrews on 21/08/2026.
 */

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HelpScreen(
    url: String, viewModel: MainViewModel
) {
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    val focusRequester = remember { FocusRequester() }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { focusState ->
                if (focusState.hasFocus) {
                    viewModel.setBackInProgress(false)
                }
            }
            .focusRequester(focusRequester),
        colors = SurfaceDefaults.colors(containerColor = Color.Transparent)
    ) {

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)

                    setOnKeyListener { _, keyCode, _ ->
                        if (keyCode == KeyEvent.KEYCODE_BACK && canGoBack()) {
                            goBack()
                            true
                        } else {
                            false
                        }
                    }

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        builtInZoomControls = false
                        displayZoomControls = false
                        useWideViewPort = true
                        textZoom = 60
                        loadWithOverviewMode = true
                        isVerticalScrollBarEnabled = false
                        cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    }

                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    webViewClient = object : WebViewClient() {
                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: RenderProcessGoneDetail?
                        ): Boolean {
                            view?.let { crashedView ->
                                (crashedView.parent as? ViewGroup)?.removeView(crashedView)
                                crashedView.destroy()
                            }
                            webViewInstance = null
                            return true
                        }
                    }
                }.also { webViewInstance = it }
            },
            modifier = Modifier.fillMaxSize(),
            update = { webView ->
                if (webView.url == null) {
                    webView.loadUrl(url)
                }
            },
            onRelease = { webView ->
                webView.stopLoading()
                webView.removeAllViews()
                webView.destroy()

                val targetRoute = viewModel.currentRoute.value

                if (targetRoute == Screens.SettingsScreen.route) {
                    viewModel.setOptionsScreenState(true)
                }
            }
        )
    }
}

