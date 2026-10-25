package link.firestar.companion

import android.content.Context

class TunnelStore(context: Context) {
    private val prefs = context.getSharedPreferences("tunnel", Context.MODE_PRIVATE)
    fun load(): TunnelCredential? = runCatching {
        val slug = SecureStore.decrypt(prefs.getString("slug", null) ?: return null).toString(Charsets.UTF_8)
        val token = SecureStore.decrypt(prefs.getString("token", null) ?: return null).toString(Charsets.UTF_8)
        TunnelCredential(slug, token)
    }.getOrNull()
    fun save(credential: TunnelCredential) {
        prefs.edit().putString("slug", SecureStore.encrypt(credential.slug.toByteArray()))
            .putString("token", SecureStore.encrypt(credential.token.toByteArray())).putLong("verifiedAt", System.currentTimeMillis()).commit()
    }
    fun clear() { prefs.edit().clear().commit() }
    fun verified() = prefs.getLong("verifiedAt", 0) > 0 && load() != null
}
