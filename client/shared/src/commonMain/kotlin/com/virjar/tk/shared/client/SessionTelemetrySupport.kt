package com.virjar.tk.shared.client

import com.virjar.tk.shared.log.LogBuffer
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import kotlinx.coroutines.CancellationException

/** 遥测存储是 best-effort，绝不能使一个本可用的会话不可用。 */
internal fun <T> bestEffortSessionTelemetry(
    enabled: Boolean,
    localDiagnostics: LogBuffer,
    localDiagnostic: (level: String, tag: String, message: String) -> Unit = { level, tag, message ->
        localDiagnostics.append(level, tag, message)
    },
    platformDiagnostic: (String) -> Unit = { message ->
        PlatformOnlyTkLogger(SESSION_TELEMETRY_DIAGNOSTIC_TAG).fault(message)
    },
    factory: () -> T,
): T? {
    if (!enabled) return null
    return try {
        factory()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        try {
            localDiagnostic(
                "fault",
                SESSION_TELEMETRY_DIAGNOSTIC_TAG,
                SESSION_TELEMETRY_DISABLED_MESSAGE,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 本地诊断是可选的；失败的 sink 绝不能恢复该依赖。
        }
        try {
            platformDiagnostic(SESSION_TELEMETRY_DISABLED_MESSAGE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // best-effort 诊断绝不能恢复遥测/会话依赖。
        }
        null
    }
}

internal const val SESSION_TELEMETRY_DIAGNOSTIC_TAG = "ClientTelemetry"
internal const val SESSION_TELEMETRY_DISABLED_MESSAGE = "Structured telemetry disabled for this session"
