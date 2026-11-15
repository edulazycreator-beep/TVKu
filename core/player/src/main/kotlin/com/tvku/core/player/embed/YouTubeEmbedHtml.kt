package com.tvku.core.player.embed

/**
 * Builds the page that hosts YouTube's official IFrame player.
 *
 * Follows YouTube API Services "Required Minimum Functionality":
 * - the client identifies itself through the HTTP Referer: the page is loaded
 *   with `loadDataWithBaseURL(origin, ...)`, and the same value is passed as
 *   `origin` / `widget_referrer`;
 * - the player fills its container and nothing is drawn over it;
 * - no undocumented player modifications.
 */
object YouTubeEmbedHtml {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val ORIGIN = Regex("^https://[a-z0-9]+(\\.[a-z0-9]+)+$")

    /** Stable reverse-DNS identity derived from the app ID, without build-type suffixes. */
    const val TVKU_ORIGIN = "https://com.tvku.app"

    fun build(videoId: String, origin: String = TVKU_ORIGIN, autoplay: Boolean = true): String {
        require(VIDEO_ID.matches(videoId)) { "invalid YouTube video id" }
        require(ORIGIN.matches(origin)) { "origin must be an https reverse-DNS app domain" }
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta name="referrer" content="strict-origin-when-cross-origin">
              <style>html,body{margin:0;padding:0;height:100%;background:#000;overflow:hidden}#player{position:absolute;inset:0;width:100%;height:100%}</style>
            </head>
            <body>
              <div id="player"></div>
              <script src="https://www.youtube.com/iframe_api"></script>
              <script>
                function onYouTubeIframeAPIReady() {
                  new YT.Player('player', {
                    width: '100%',
                    height: '100%',
                    videoId: '$videoId',
                    playerVars: {
                      autoplay: ${if (autoplay) 1 else 0},
                      playsinline: 1,
                      origin: '$origin',
                      widget_referrer: '$origin'
                    }
                  });
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
