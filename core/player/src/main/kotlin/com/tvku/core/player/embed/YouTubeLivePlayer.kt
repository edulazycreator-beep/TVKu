package com.tvku.core.player.embed

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * YouTube's official embedded player, in the OS-provided [WebView], showing
 * the current live broadcast of [youtubeChannelId].
 *
 * Callers must show it as the visible, primary content (autoplay rule), must
 * not draw anything over it (overlay rule) and must keep it at least
 * 200 x 200 dp (enforced here).
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun YouTubeLivePlayer(
    youtubeChannelId: String,
    onStatus: (YouTubePlayerStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val html = YouTubeEmbedHtml.liveChannel(youtubeChannelId)
    val currentOnStatus by rememberUpdatedState(onStatus)
    AndroidView(
        modifier = modifier.sizeIn(minWidth = 200.dp, minHeight = 200.dp),
        factory = { context ->
            val main = Handler(Looper.getMainLooper())
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true          // required by the IFrame Player API
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webChromeClient = WebChromeClient()
                // Only primitive values cross this bridge, and they only update UI state.
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onPlayerEvent(kind: String?, code: Int) {
                        val status = YouTubePlayerStatus.from(kind.orEmpty(), code)
                        main.post { currentOnStatus(status) }
                    }
                }, YouTubeEmbedHtml.BRIDGE)
                tag = youtubeChannelId
                // Base URL sets the HTTP Referer that identifies TVKu to YouTube.
                loadDataWithBaseURL(YouTubeEmbedHtml.TVKU_ORIGIN, html, "text/html", "utf-8", null)
            }
        },
        update = { view ->
            if (view.tag != youtubeChannelId) {
                view.tag = youtubeChannelId
                view.loadDataWithBaseURL(YouTubeEmbedHtml.TVKU_ORIGIN, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { view ->
            view.stopLoading()
            view.removeJavascriptInterface(YouTubeEmbedHtml.BRIDGE)
            view.loadUrl("about:blank")
            view.destroy()
        },
    )
}
