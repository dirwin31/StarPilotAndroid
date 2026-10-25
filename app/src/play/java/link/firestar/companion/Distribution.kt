package link.firestar.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

object Distribution : AppDistribution {
    override val selfUpdates = false
    override fun initialize(ctx: Context) {}
    override fun checkAsync(ctx: Context) {}
    override fun opened(ctx: Context) {}
    override fun requestUpdate(activity: Activity, origin: String) {
        val id = BuildConfig.APPLICATION_ID
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id"))) }
        catch (_: android.content.ActivityNotFoundException) { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))) }
    }
}
