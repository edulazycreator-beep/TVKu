package com.tvku.core.player.external

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tvku.core.model.PlaybackSource

/**
 * Opens a broadcaster's official platform page. The intent is a plain
 * ACTION_VIEW on an https URL, so Android routes it to the platform's own app
 * (via its verified App Links) or to the browser. TVKu never reads, proxies or
 * re-streams the content behind it.
 */
object OfficialPlatformLauncher {
    fun intentFor(link: PlaybackSource.ExternalLink): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(link.url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** @return false when no app on the device can open the link. */
    fun open(context: Context, link: PlaybackSource.ExternalLink): Boolean = try {
        context.startActivity(intentFor(link))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
