package com.tvku.core.player.embed

/**
 * Builds the page hosting YouTube's official embedded player for a channel's
 * current live broadcast (`/embed/live_stream?channel=<id>`).
 *
 * Follows YouTube API Services "Required Minimum Functionality":
 * - the client identifies itself through the HTTP Referer: the page is loaded
 *   with `loadDataWithBaseURL(origin, ...)`, and the same value is passed as
 *   `origin` / `widget_referrer`;
 * - the player fills its container and nothing is drawn over it;
 * - the official IFrame API is only used to *listen* to player events, so the
 *   app can show the real state (never a fake LIVE badge).
 */
object YouTubeEmbedHtml {
    private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
    private val ORIGIN = Regex("^https://[a-z0-9]+(\\.[a-z0-9]+)+$")

    /** Stable reverse-DNS identity derived from the app ID, without build-type suffixes. */
    const val TVKU_ORIGIN = "https://com.tvku.app"

    /** Name of the JavaScript interface receiving player events. */
    const val BRIDGE = "TvkuPlayerBridge"

    fun liveChannel(channelId: String, origin: String = TVKU_ORIGIN): String {
        require(CHANNEL_ID.matches(channelId)) { "invalid YouTube channel id" }
        require(ORIGIN.matches(origin)) { "origin must be an https reverse-DNS app domain" }
        val src = "https://www.youtube.com/embed/live_stream?channel=$channelId" +
            "&autoplay=1&playsinline=1&enablejsapi=1&rel=0&origin=$origin&widget_referrer=$origin"
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta name="referrer" content="strict-origin-when-cross-origin">
              <style>html,body{margin:0;padding:0;height:100%;background:#000;overflow:hidden}#player{position:absolute;inset:0;width:100%;height:100%;border:0}</style>
            </head>
            <body>
              <iframe id="player" src="$src" title="YouTube live player"
                allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen
                referrerpolicy="strict-origin-when-cross-origin"></iframe>
              <script src="https://www.youtube.com/iframe_api"></script>
              <script>
                function report(kind, code) { try { window.$BRIDGE.onPlayerEvent(kind, code); } catch (e) {} }
                function onYouTubeIframeAPIReady() {
                  new YT.Player('player', { events: {
                    onReady: function () { report('ready', 0); },
                    onStateChange: function (e) { report('state', e.data); },
                    onError: function (e) { report('error', e.data); }
                  }});
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
