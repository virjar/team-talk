package com.virjar.tk.android

import android.os.Build
import com.virjar.tk.android.BuildConfig
import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.protocol.telemetry.ClientPlatform
import com.virjar.tk.protocol.telemetry.ClientRuntimeInfo
import com.virjar.tk.protocol.telemetry.ClientTelemetryLimits

internal fun androidClientRuntimeInfo(): ClientRuntimeInfo = androidClientRuntimeInfo(
    osVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
    architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
    appVersion = BuildConfig.VERSION_NAME,
    buildNumber = BuildConfig.VERSION_CODE.toString(),
    gitCommit = BuildConfig.GIT_COMMIT_ID,
    buildIdentity = BuildConfig.BUILD_IDENTITY,
    buildTime = BuildConfig.BUILD_TIME,
    distribution = "android-${BuildConfig.BUILD_TYPE}",
)

internal fun androidClientRuntimeInfo(
    osVersion: String,
    architecture: String,
    deviceModel: String,
    appVersion: String,
    buildNumber: String,
    gitCommit: String,
    buildIdentity: String,
    buildTime: String,
    distribution: String,
): ClientRuntimeInfo = ClientRuntimeInfo(
    platform = ClientPlatform.ANDROID,
    osName = "Android",
    osVersion = safeRuntimeField(osVersion),
    architecture = safeRuntimeField(architecture),
    deviceModel = safeRuntimeField(deviceModel),
    appVersion = safeRuntimeField(appVersion),
    buildNumber = safeRuntimeField(buildNumber),
    gitCommit = safeRuntimeField(gitCommit, ClientTelemetryLimits.MAX_GIT_COMMIT_CHARS),
    buildIdentity = safeRuntimeField(
        buildIdentity,
        ClientTelemetryLimits.MAX_BUILD_IDENTITY_CHARS,
    ),
    buildTime = safeRuntimeField(buildTime, ClientTelemetryLimits.MAX_BUILD_TIME_CHARS),
    protocolVersion = ProtocolVersions.CURRENT_ID,
    distribution = safeRuntimeField(distribution),
)

private fun safeRuntimeField(
    value: String,
    maxChars: Int = ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS,
): String = value
    .map { character -> if (character.isISOControl()) ' ' else character }
    .joinToString(separator = "")
    .trim()
    .take(maxChars)
    .ifBlank { "unknown" }
