package com.virjar.tk.desktop

import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.protocol.telemetry.ClientPlatform
import com.virjar.tk.protocol.telemetry.ClientRuntimeInfo
import com.virjar.tk.protocol.telemetry.ClientTelemetryLimits

internal fun desktopClientRuntimeInfo(): ClientRuntimeInfo = desktopClientRuntimeInfo(
    osName = System.getProperty("os.name", "unknown"),
    osVersion = System.getProperty("os.version", "unknown"),
    architecture = System.getProperty("os.arch", "unknown"),
    appVersion = BuildConfig.APP_VERSION,
    buildNumber = BuildConfig.BUILD_NUMBER.toString(),
    gitCommit = BuildConfig.GIT_COMMIT_ID,
    buildIdentity = BuildConfig.BUILD_IDENTITY,
    buildTime = BuildConfig.BUILD_TIME,
)

internal fun desktopClientRuntimeInfo(
    osName: String,
    osVersion: String,
    architecture: String,
    appVersion: String,
    buildNumber: String,
    gitCommit: String,
    buildIdentity: String,
    buildTime: String,
): ClientRuntimeInfo = ClientRuntimeInfo(
    platform = ClientPlatform.DESKTOP,
    osName = safeDesktopRuntimeField(osName),
    osVersion = safeDesktopRuntimeField(osVersion),
    architecture = safeDesktopRuntimeField(architecture),
    deviceModel = "Desktop",
    appVersion = safeDesktopRuntimeField(appVersion),
    buildNumber = safeDesktopRuntimeField(buildNumber),
    gitCommit = safeDesktopRuntimeField(gitCommit, ClientTelemetryLimits.MAX_GIT_COMMIT_CHARS),
    buildIdentity = safeDesktopRuntimeField(
        buildIdentity,
        ClientTelemetryLimits.MAX_BUILD_IDENTITY_CHARS,
    ),
    buildTime = safeDesktopRuntimeField(buildTime, ClientTelemetryLimits.MAX_BUILD_TIME_CHARS),
    protocolVersion = ProtocolVersions.CURRENT_ID,
    distribution = "compose-desktop",
)

private fun safeDesktopRuntimeField(
    value: String,
    maxChars: Int = ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS,
): String = value
    .map { character -> if (character.isISOControl()) ' ' else character }
    .joinToString(separator = "")
    .trim()
    .take(maxChars)
    .ifBlank { "unknown" }
