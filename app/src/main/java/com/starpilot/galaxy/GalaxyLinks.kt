package com.starpilot.galaxy

import java.net.URI

object GalaxyLinks {
    /** External pages and package downloads open outside the WebView carrying our bridge. */
    fun external(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.port in setOf(-1, 443)
    }.getOrDefault(false)
}
