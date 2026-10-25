package link.firestar.companion

import android.content.Context
class PairingStore(context: Context) {
    companion object { private val PHONE_ID = Regex("[0-9a-f]{8}") }
    private val prefs = context.getSharedPreferences("bluetooth-pairing", Context.MODE_PRIVATE)
    /** [id] is comma's id for this phone's key (newer comma). The id it replaces is kept so comma can drop that entry. */
    fun save(value: ByteArray, id: String? = null) {
        require(value.size == 32)
        val previous = id()
        prefs.edit().remove("deviceAddress").remove("stale").remove("musicAddress").putString("key", SecureStore.encrypt(value))
            .apply { if (id != null && PHONE_ID.matches(id)) putString("id", id) else remove("id") }
            .apply { if (previous != null && previous != id) putString("previousId", previous) else remove("previousId") }
            .commit()
    }
    fun id(): String? = prefs.getString("id", null)?.takeIf { PHONE_ID.matches(it) }
    fun previousId(): String? = prefs.getString("previousId", null)?.takeIf { PHONE_ID.matches(it) }
    fun load(): ByteArray? = try {
        prefs.getString("key", null)?.let {
            SecureStore.decrypt(it).also { decoded -> require(decoded.size == 32) }
        }
    } catch (_: Exception) { null }
    fun rememberDevice(address: String) {
        require(address.matches(Regex("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}")))
        prefs.edit().putString("deviceAddress", address.uppercase()).apply()
    }
    fun deviceAddress(): String? = prefs.getString("deviceAddress", null)?.takeIf {
        it.matches(Regex("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}"))
    }
    /** Comma's audio address once this phone bonded with it, so forgetting comma in Bluetooth settings can drop the app pairing too. */
    fun rememberMusic(address: String) {
        if (address.matches(Regex("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}"))) prefs.edit().putString("musicAddress", address.uppercase()).apply()
    }
    fun musicAddress(): String? = prefs.getString("musicAddress", null)
    /** Comma stopped recognizing this key (forgotten there); the app stops reconnecting until it is paired again. */
    fun markStale() { prefs.edit().putBoolean("stale", true).apply() }
    fun stale(): Boolean = prefs.getBoolean("stale", false)
    fun forget() { prefs.edit().clear().commit() }
}
