package com.starpilot.auto

import android.content.Context

/** Explicit, local reviewer mode. It never changes the primary Galaxy app. */
internal object ReviewMode {
    private const val PREFERENCES = "review_demo"
    private const val ACTIVE = "active"

    fun isActive(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ACTIVE, false)

    fun setActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(ACTIVE, active).apply()
    }
}
