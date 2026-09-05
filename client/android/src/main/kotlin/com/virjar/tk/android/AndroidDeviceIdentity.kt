package com.virjar.tk.android

import android.content.Context
import com.virjar.tk.protocol.model.AuthRules
import java.util.UUID

/**
 * 安装作用域内的身份标识，被所有 Android 认证路径使用。
 *
 * 它有意在进程和 Activity 重建后仍然存活，而卸载应用则会创建一个新身份。
 * 密码登录、注册和刷新认证都必须使用这个确切的值，
 * 这样服务器看到的就是同一台物理安装，而不是每次登录都当成一台新设备。
 */
internal object AndroidDeviceIdentity {
    private const val PREFERENCES = "teamtalk_device"
    private const val KEY_INSTALLATION_ID = "installation_id"

    fun getOrCreate(context: Context): String {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(KEY_INSTALLATION_ID, null)
            ?.takeIf { AuthRules.validateDeviceId(it) == null }
            ?.let { return it }
        return synchronized(this) {
            preferences.getString(KEY_INSTALLATION_ID, null)
                ?.takeIf { AuthRules.validateDeviceId(it) == null }
                ?: "android-${UUID.randomUUID()}".also { generated ->
                    // commit() 让身份在认证开始之前持久化。如果改用异步 apply() 后进程随即死亡，
                    // 则可能创建出第二台设备。
                    check(preferences.edit().putString(KEY_INSTALLATION_ID, generated).commit()) {
                        "无法保存设备标识"
                    }
                }
        }
    }
}
