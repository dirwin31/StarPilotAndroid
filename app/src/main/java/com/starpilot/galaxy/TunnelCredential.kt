package com.starpilot.galaxy

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class TunnelCredential(val slug: String, val token: String) {
    init { require(SLUG.matches(slug) && SECRET.matches(token)) }
    val sessionToken: String get() = URLEncoder.encode("$slug:$token", "UTF-8")
    val webUrl: String get() = "https://galaxy.firestar.link/$slug/"
    companion object {
        val SLUG = Regex("[A-Za-z0-9]{16}")
        private val SECRET = Regex("[0-9a-f]{64}")
        fun parse(text: String): TunnelCredential? = runCatching {
            require(text.length <= 4096)
            var raw = text.trim()
            if (raw.startsWith("starpilot-remote://")) {
                val uri = URI(raw)
                require(uri.scheme == "starpilot-remote" && uri.host == "add" && uri.userInfo == null && uri.port == -1)
                val keys = uri.rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "key" }
                require(keys.size == 1)
                raw = URLDecoder.decode(keys.single().substringAfter('='), "UTF-8")
            }
            raw = URLDecoder.decode(raw, "UTF-8")
            val parts = raw.split(':')
            require(parts.size == 2)
            TunnelCredential(parts[0], parts[1])
        }.getOrNull()
        fun galaxyId(text: String): String? = runCatching {
            val raw = text.trim()
            if (SLUG.matches(raw)) raw else {
                val uri = URI(raw)
                require(uri.scheme == "https" && uri.host == "galaxy.firestar.link" && uri.userInfo == null && uri.port == -1)
                uri.path.trim('/').also { require(SLUG.matches(it)) }
            }
        }.getOrNull()
    }
}
