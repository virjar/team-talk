package com.virjar.tk.server.domain.auth

/**
 * 持久化凭证签发与实时连接准入共享的、按账户的硬性上限。
 *
 * 该产品刻意不在此处开放配置：修改这个值会同时改变持久化聚合不变量与运行时安全边界，
 * 因此两者必须一起改动并一起测试。
 */
object AuthenticatedDevicePolicy {
    const val MAX_DEVICES_PER_USER = 16
}

/** 预期的凭证签发结果；它不携带任何 uid、设备 id、数量或数据库细节。 */
class AuthenticatedDeviceLimitReachedException : RuntimeException("Authenticated device limit reached")
