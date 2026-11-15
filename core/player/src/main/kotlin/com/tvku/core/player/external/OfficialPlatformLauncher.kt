package com.tvku.core.player.external

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tvku.core.model.ChannelSource

/**
 * Opens a broadcaster's official platform page with a plain ACTION_VIEW
 * intent, so Android routes it to the platform's own app (via its verified
 * App Links) or to the browser. TVKu never reads, proxies or re-streams it.
 */
object OfficialPlatformLauncher {
    fun intentFor(link: ChannelSource.OfficialLink): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(link.url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** @return false when no app on the device can open the link. */
    fun open(context: Context, link: ChannelSource.OfficialLink): Boolean = try {
        context.startActivity(intentFor(link))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
