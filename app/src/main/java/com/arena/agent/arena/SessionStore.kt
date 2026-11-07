package com.arena.agent.arena

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager

class SessionStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("arena_session", Context.MODE_PRIVATE)

    fun hasSavedLogin(): Boolean = prefs.getBoolean(KEY_LOGGED_IN, false)

    fun markLoggedIn(cookies: String?) {
        prefs.edit()
            .putBoolean(KEY_LOGGED_IN, true)
            .putString(KEY_COOKIES, cookies.orEmpty())
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_LOGGED_IN).remove(KEY_COOKIES).apply()
    }

    fun restoreCookies() {
        val saved = prefs.getString(KEY_COOKIES, null).orEmpty()
        if (saved.isBlank()) return
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        saved.split(';')
            .map { it.trim() }
            .filter { it.contains('=') }
            .forEach { pair ->
                manager.setCookie(ArenaUrls.COOKIE_URL, pair)
                manager.setCookie("https://www.arena.ai", pair)
            }
        manager.flush()
    }

    private companion object {
        const val KEY_LOGGED_IN = "logged_in"
        const val KEY_COOKIES = "cookie_header"
    }
}
