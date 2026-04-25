package com.virjar.tk.client

import com.virjar.tk.app.BuildConfig

actual fun getDefaultServerBaseUrl(): String = BuildConfig.SERVER_BASE_URL

actual fun getDefaultTcpHost(): String = BuildConfig.TCP_HOST

actual fun getDefaultTcpPort(): Int = BuildConfig.TCP_PORT

actual fun getBuildProfile(): String = BuildConfig.BUILD_PROFILE

actual fun isShowAdvancedSettings(): Boolean = BuildConfig.SHOW_ADVANCED_SETTINGS
