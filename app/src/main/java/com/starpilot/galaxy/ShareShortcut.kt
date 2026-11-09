package com.starpilot.galaxy

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Build
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/** Puts comma in the share sheet's top row (Direct Share, Android 10+); res/xml/shortcuts.xml routes it to ShareActivity. */
object ShareShortcut {
    const val CATEGORY = "com.starpilot.galaxy.category.ROUTE"
    private const val ID = "comma"

    fun publish(context: Context) { runCatching {
        val label = context.getString(R.string.share_shortcut)
        val shortcut = ShortcutInfoCompat.Builder(context, ID).setShortLabel(label).setLongLabel(context.getString(R.string.share_shortcut_long))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            .setCategories(setOf(CATEGORY)).setLongLived().setPerson(Person.Builder().setName(label).setKey(ID).build()).build()
        if (Build.VERSION.SDK_INT >= 30) context.getSystemService(ShortcutManager::class.java).pushDynamicShortcut(shortcut.toShortcutInfo())
        else ShortcutManagerCompat.addDynamicShortcuts(context, listOf(shortcut))
    } }
}
