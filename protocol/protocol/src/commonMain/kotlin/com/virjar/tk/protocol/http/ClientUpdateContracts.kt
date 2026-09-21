package com.virjar.tk.protocol.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 统一客户端更新体系的 HTTP 契约（服务端与桌面/无头客户端共享）。
 *
 * 客户端只负责执行 HTTP 与本地文件操作，不拥有响应格式；词汇表与
 * [AndroidReleaseManifest] 的通道常量保持一致。所有 url 字段都是
 * 站点根相对路径（如 `/api/v1/client/files/<sha>`），客户端自行拼接服务器基地址。
 */
object ClientUpdateContracts {
    const val CLIENT_DESKTOP = "desktop"
    const val CLIENT_ANDROID = "android"
    const val CLIENT_IOS = "ios"
    const val CLIENT_HEADLESS = "headless"

    const val PLATFORM_MACOS = "macos"
    const val PLATFORM_WINDOWS = "windows"
    const val PLATFORM_LINUX = "linux"
    const val PLATFORM_ANDROID = "android"
    const val PLATFORM_IOS = "ios"
    const val PLATFORM_ANY = "any"

    const val ARCH_AMD64 = "amd64"
    const val ARCH_AARCH64 = "aarch64"
    const val ARCH_ANY = "any"

    const val STATUS_UP_TO_DATE = "UP_TO_DATE"
    const val STATUS_UPDATE_AVAILABLE = "UPDATE_AVAILABLE"
    const val STATUS_SHELL_UPDATE_REQUIRED = "SHELL_UPDATE_REQUIRED"
    const val STATUS_CHANNEL_DISABLED = "CHANNEL_DISABLED"

    const val KIND_PAYLOAD = "payload"
    const val KIND_BUNDLE = "bundle"
    const val KIND_INSTALLER = "installer"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = false
    }
}

/** `GET /api/v1/client/updates/check` 的响应。 */
@Serializable
data class ClientUpdateCheckResponse(
    val status: String,
    val release: ClientReleaseInfo? = null,
)

/**
 * 一个可下载发布的信息。桌面负载更新走 [manifestUrl] 文件清单做本地 diff；
 * 无头/CLI 走 [bundleUrl] 全量包；首装或壳升级走 [installers]。
 */
@Serializable
data class ClientReleaseInfo(
    val id: Long,
    val clientType: String,
    val platform: String,
    val arch: String,
    val version: String,
    val build: Long,
    val channel: String,
    val notes: String? = null,
    val forced: Boolean = false,
    val minShellAbi: Int? = null,
    val manifestUrl: String? = null,
    val bundleUrl: String? = null,
    val fileCount: Int = 0,
    val totalBytes: Long = 0,
    val installers: List<ClientInstallerInfo> = emptyList(),
    val buildIdentity: String? = null,
)

@Serializable
data class ClientInstallerInfo(
    val label: String,
    val filename: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

/** `GET /api/v1/client/releases/{id}/manifest.json` 的响应：负载文件清单。 */
@Serializable
data class ClientReleaseManifest(
    val releaseId: Long,
    val clientType: String,
    val platform: String,
    val arch: String,
    val version: String,
    val build: Long,
    val minShellAbi: Int? = null,
    val files: List<ClientPayloadFile>,
    val buildIdentity: String? = null,
)

@Serializable
data class ClientPayloadFile(
    val path: String,
    val sha256: String,
    val size: Long,
    /** POSIX 权限位（可执行文件需要）；null = 由客户端按平台默认处理。 */
    val mode: Int? = null,
    val url: String,
)
