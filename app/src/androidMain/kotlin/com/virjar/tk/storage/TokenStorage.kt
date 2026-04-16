package com.virjar.tk.storage

import android.content.Context

actual class TokenStorage actual constructor() {
    private val prefs = appContext.getSharedPreferences("teamtalk_session", Context.MODE_PRIVATE)

    actual fun save(token: String, uid: String, userJson: String) {
        prefs.edit()
            .putString("token", token)
            .putString("uid", uid)
            .putString("user", userJson)
            .apply()
    }

    actual fun loadToken(): String? = prefs.getString("token", null)
    actual fun loadUid(): String? = prefs.getString("uid", null)
    actual fun loadUserJson(): String? = prefs.getString("user", null)

    actual fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private lateinit var appContext: Context

        fun init(context: Context) {
            appContext = context.applicationContext
        }
    }
}
