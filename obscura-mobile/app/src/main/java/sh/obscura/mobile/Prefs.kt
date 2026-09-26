package sh.obscura.mobile

import android.content.Context

/** Tiny persistent settings for the last used server. */
object Prefs {
    private const val NAME = "obscura_mobile"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_TOKEN = "token"

    private fun store(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun host(c: Context): String = store(c).getString(KEY_HOST, "127.0.0.1") ?: "127.0.0.1"

    fun port(c: Context): String = store(c).getString(KEY_PORT, "9222") ?: "9222"

    fun token(c: Context): String = store(c).getString(KEY_TOKEN, "") ?: ""

    fun save(c: Context, host: String, port: String, token: String) {
        store(c).edit()
            .putString(KEY_HOST, host)
            .putString(KEY_PORT, port)
            .putString(KEY_TOKEN, token)
            .apply()
    }
}
