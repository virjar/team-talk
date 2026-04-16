package com.virjar.tk.client

import android.os.Build
import com.virjar.tk.app.BuildConfig

actual fun getDefaultServerBaseUrl(): String {
    return if (isEmulator()) "http://10.0.2.2:8080" else BuildConfig.SERVER_BASE_URL
}

actual fun getDefaultTcpHost(): String {
    return if (isEmulator()) "10.0.2.2" else BuildConfig.TCP_HOST
}

actual fun getDefaultTcpPort(): Int = BuildConfig.TCP_PORT

private fun isEmulator(): Boolean {
    return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
        || Build.FINGERPRINT.startsWith("generic")
        || Build.HARDWARE.contains("goldfish")
        || Build.HARDWARE.contains("ranchu")
        || Build.MODEL.contains("google_sdk")
        || Build.MODEL.contains("Emulator")
        || Build.PRODUCT.contains("sdk_gphone")
}
