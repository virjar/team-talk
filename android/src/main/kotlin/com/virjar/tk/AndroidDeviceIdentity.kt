package com.virjar.tk

import android.content.Context
import java.util.UUID

/**
 * Installation-scoped identity used by every Android authentication path.
 *
 * It intentionally survives process and Activity recreation, while uninstalling the app creates
 * a new identity. Password login, registration and refresh authentication must all use this exact
 * value so the server sees one physical installation rather than a new device on every login.
 */
internal object AndroidDeviceIdentity {
    private const val PREFERENCES = "teamtalk_device"
    private const val KEY_INSTALLATION_ID = "installation_id"

    fun getOrCreate(context: Context): String {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(KEY_INSTALLATION_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        return synchronized(this) {
            preferences.getString(KEY_INSTALLATION_ID, null)?.takeIf { it.isNotBlank() }
                ?: "android-${UUID.randomUUID()}".also { generated ->
                    // commit() makes the identity durable before authentication starts. An async
                    // apply() followed by process death could otherwise create a second device.
                    check(preferences.edit().putString(KEY_INSTALLATION_ID, generated).commit()) {
                        "无法保存设备标识"
                    }
                }
        }
    }
}
