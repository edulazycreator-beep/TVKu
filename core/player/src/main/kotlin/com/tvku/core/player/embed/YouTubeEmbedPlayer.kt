package com.tvku.core.player.embed

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Official YouTube embedded player in the OS-provided [WebView].
 *
 * Callers must show this composable only when it is the visible, primary
 * content of the screen (autoplay rule) and must not draw anything on top of
 * it (overlay rule). Size is never below 200 x 200 dp.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeEmbedPlayer(videoId: String, modifier: Modifier = Modifier) {
    val html = YouTubeEmbedHtml.build(videoId)
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .sizeIn(minWidth = 200.dp, minHeight = 200.dp),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true          // required by the IFrame Player API
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webChromeClient = WebChromeClient()        // fullscreen + media support
                // Base URL sets the HTTP Referer that identifies TVKu to YouTube.
                loadDataWithBaseURL(YouTubeEmbedHtml.TVKU_ORIGIN, html, "text/html", "utf-8", null)
            }
        },
        update = { view ->
            if (view.tag != videoId) {
                view.tag = videoId
                view.loadDataWithBaseURL(YouTubeEmbedHtml.TVKU_ORIGIN, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { view ->
            view.stopLoading()
            view.loadUrl("about:blank")
            view.destroy()
        },
    )
}
