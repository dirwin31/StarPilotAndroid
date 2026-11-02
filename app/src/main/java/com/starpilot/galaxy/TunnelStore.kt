package com.starpilot.galaxy

import android.content.Context

class TunnelStore internal constructor(context: Context,
    private val encrypt: (ByteArray) -> String = SecureStore::encrypt,
    private val decrypt: (String) -> ByteArray = SecureStore::decrypt) {
    private val prefs = context.getSharedPreferences("tunnel", Context.MODE_PRIVATE)
    fun load(): TunnelCredential? = runCatching {
        val slug = decrypt(prefs.getString("slug", null) ?: return null).toString(Charsets.UTF_8)
        val token = decrypt(prefs.getString("token", null) ?: return null).toString(Charsets.UTF_8)
        TunnelCredential(slug, token)
    }.getOrNull()
    fun save(credential: TunnelCredential, verified: Boolean = true) {
        check(prefs.edit().putString("slug", encrypt(credential.slug.toByteArray()))
            .putString("token", encrypt(credential.token.toByteArray()))
            .putLong("verifiedAt", if (verified) System.currentTimeMillis() else 0).commit()) { "Couldn't save Galaxy pairing" }
    }
    fun clear() { prefs.edit().clear().commit() }
    fun verified() = prefs.getLong("verifiedAt", 0) > 0 && load() != null
}
