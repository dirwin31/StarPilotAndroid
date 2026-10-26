package link.firestar.companion

import android.os.Build
import android.view.WindowInsets

internal data class SystemBarInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal fun systemBarInsets(insets: WindowInsets): SystemBarInsets =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        insets.getInsets(WindowInsets.Type.systemBars()).let {
            SystemBarInsets(it.left, it.top, it.right, it.bottom)
        }
    } else {
        legacySystemBarInsets(insets)
    }

@Suppress("DEPRECATION")
private fun legacySystemBarInsets(insets: WindowInsets) = SystemBarInsets(
    insets.systemWindowInsetLeft,
    insets.systemWindowInsetTop,
    insets.systemWindowInsetRight,
    insets.systemWindowInsetBottom
)
