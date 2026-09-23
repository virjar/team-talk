package com.virjar.tk.server.domain.message

import kotlinx.serialization.Serializable

/** 服务号官方触达的持久化端口；由基础设施适配器实现。 */
interface ServiceAccountDirectory {
    fun getWelcomeTemplate(): String?
    fun setWelcomeTemplate(content: String, updatedBy: String)
    fun insertBroadcast(broadcastId: String, markdown: String, createdBy: String)
    fun getBroadcast(broadcastId: String): ServiceBroadcastEntry?
    fun listBroadcasts(limit: Int = 50): List<ServiceBroadcastEntry>
    fun listUnfinishedBroadcastIds(): List<String>
    fun markBroadcastTotal(broadcastId: String, totalUsers: Int)
    fun recordBroadcastProgress(broadcastId: String, coveredUsers: Int, failedUsers: Int, lastError: String?)
    fun finishBroadcast(broadcastId: String)
}

/** 广播台账条目；计数为最近一次执行的结果。经 /api/admin/service 以 JSON 响应。 */
@Serializable
data class ServiceBroadcastEntry(
    val broadcastId: String,
    val markdown: String,
    val createdBy: String,
    val createdAt: Long,
    val finishedAt: Long?,
    val totalUsers: Int,
    val coveredUsers: Int,
    val failedUsers: Int,
    val lastError: String?,
)
