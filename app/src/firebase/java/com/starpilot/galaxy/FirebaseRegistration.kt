package com.starpilot.galaxy

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging

object FirebaseRegistration {
    const val available = true
    fun stop(ctx: Context) { runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = false } }
    fun start(ctx: Context) {
        runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = true; FirebaseMessaging.getInstance().token.addOnSuccessListener { SentryPush.token(ctx, it) } }
    }
}
