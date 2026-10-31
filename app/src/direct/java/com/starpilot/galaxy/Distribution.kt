package com.starpilot.galaxy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

object Distribution : AppDistribution {
    override val selfUpdates = true
    private var pendingOrigin: String? = null
    override fun initialize(ctx: Context) = AppUpdates.initialize(ctx)
    override fun checkAsync(ctx: Context) = AppUpdates.checkAsync(ctx)
    override fun opened(ctx: Context) = AppUpdateCompletion.opened(ctx)
    override fun requestUpdate(activity: Activity, origin: String) {
        val local = Networks.localOrigin(origin)
        fun message(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
        if (local == null) { message("Connect using Galaxy Wi-Fi or LAN to download the app update."); return }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            pendingOrigin = local
            message("Allow Galaxy to install its downloaded update, then return.")
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        pendingOrigin = null
        val network = (if (local == Networks.ORIGIN) Networks.wifi(activity) else Networks.wifi(activity, false))?.first
        if (network == null) { message("Reconnect to the same Wi-Fi as StarPilot, then try the update again."); return }
        if (!AppInstaller.start(activity, network, local)) message("An app update is already in progress.")
    }
    override fun resume(activity: Activity) {
        pendingOrigin?.let { if (activity.packageManager.canRequestPackageInstalls()) requestUpdate(activity, it) }
    }
}
