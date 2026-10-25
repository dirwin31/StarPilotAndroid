package link.firestar.companion

import android.app.Activity
import android.content.Context

interface AppDistribution {
    val selfUpdates: Boolean
    fun initialize(ctx: Context)
    fun checkAsync(ctx: Context)
    fun opened(ctx: Context)
    fun requestUpdate(activity: Activity, origin: String)
    fun resume(activity: Activity) {}
    companion object { const val ORIGIN_EXTRA = "updateOrigin" }
}
