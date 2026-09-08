package com.virjar.tk.app.navigation.feature.task

import com.virjar.tk.app.ui.screen.conversationIdentityPresentation
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.TaskRefBody
import com.virjar.tk.protocol.model.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun TaskFeature.rememberTaskUsers(tasks: List<WorkTask>) =
    rememberUsers(tasks.flatMap { listOf(it.creatorUid, it.assigneeUid) })

/** 昵称读取只补当前有界页面引用的用户，不枚举组织或聊天成员。 */
internal fun TaskFeature.rememberUsers(ids: List<String>) {
    val missing = ids.distinct().filter { it !in users && it !in requestedUsers }.take(400)
    if (missing.isEmpty()) return
    requestedUsers += missing
    scope.launch {
        val cached = localData.run { missing.mapNotNull { uid -> session.localCache.getUser(uid)?.let { uid to it } }.toMap() }
        users = (users + cached).entries.toList().takeLast(512).associate { it.toPair() }
        for (uid in missing.filterNot(cached::containsKey)) {
            try {
                val user = localData.run { session.userRepo.getProfile(uid).getOrThrow() }
                if (user != null) users = (users + (uid to user)).entries.toList().takeLast(512).associate { it.toPair() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 可读任务不依赖昵称补全成功，缺失时显示通用成员名称。 */ }
        }
        // 保留一个有界失败集合，避免每个任务事件都重试缺失的个人资料。
        if (requestedUsers.size > 512) {
            val retained = requestedUsers.toList().takeLast(512)
            requestedUsers.clear()
            requestedUsers.addAll(retained)
        }
    }
}

internal fun TaskFeature.searchAssignees(query: String) {
    assigneeQuery = query.take(100)
    val keyword = assigneeQuery.trim()
    assigneeJob?.cancel()
    val owner = ++assigneeOwner
    assigneeError = null
    if (keyword.isBlank()) {
        assigneeCandidates = listOfNotNull(users[myUid])
        findingAssignees = false
        return
    }
    findingAssignees = true
    assigneeJob = scope.launch {
        try {
            delay(250)
            val result = localData.run { session.userRepo.search(keyword).getOrThrow() }
                .filter { it.role == UserRole.HUMAN && it.status == 1 }.take(50)
            if (owner != assigneeOwner) return@launch
            assigneeCandidates = result
            users = (users + result.associateBy(User::uid)).entries.toList().takeLast(512).associate { it.toPair() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (owner == assigneeOwner) { assigneeCandidates = emptyList(); assigneeError = "成员搜索暂不可用，请重试" }
        } finally { if (owner == assigneeOwner) findingAssignees = false }
    }
}

internal fun TaskFeature.loadTaskChoices() {
    rememberUsers(listOf(myUid) + listOfNotNull(editor?.assigneeUid))
    val groups = conversations.conversations.value.filter { it.chatType == ChatType.GROUP.code }
        .map { TaskContextOption(TaskPolicy.CONTEXT_GROUP, it.chatId, it.chatName ?: "群聊") }
    contextOptions = groups + contextOptions.filter { it.kind == TaskPolicy.CONTEXT_ORGANIZATION }
    if (loadingContexts) return
    loadingContexts = true
    contextError = null
    scope.launch {
        try {
            val units = localData.run { session.organizationRepo.listUnits().getOrThrow() }
                .filter { it.status == OrganizationUnit.STATUS_ACTIVE }
            val byId = units.associateBy(OrganizationUnit::unitId)
            contextOptions = groups + units.map { unit ->
                val parent = byId[unit.parentId]
                TaskContextOption(TaskPolicy.CONTEXT_ORGANIZATION, unit.unitId,
                    if (parent == null) unit.name else "${parent.name} / ${unit.name}")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { contextError = "部门列表暂不可用，已有上下文保持不变" }
        finally { loadingContexts = false }
    }
}

internal fun TaskFeature.beginShare(task: WorkTask) {
    sharing = task
    shareError = null
    sharingTo = null
    val peers = conversations.peerUsers.value
    shareOptions = conversations.conversations.value.map {
        TaskShareOption(it.chatId, conversationIdentityPresentation(it, it.peerUid?.let(peers::get)).name ?: "会话")
    }
    if (shareOptions.isEmpty()) {
        conversations.refresh()
        shareError = "暂无可选会话，请先在会话页建立或刷新会话"
    }
}

/** 与聊天使用同一 SDK 发件箱；提示持久准入，不把后台 ACK 尚未到达说成分享成功。 */
internal fun TaskFeature.share(task: WorkTask, chatId: String) {
    if (sharingTo != null) return
    val key = task.taskId to chatId
    if (key !in failedShareMessages && failedShareMessages.size >= 128) {
        shareError = "待处理的分享过多，请先重试已有分享"
        return
    }
    val message = failedShareMessages.getOrPut(key) {
        Message(chatId = chatId, clientMsgId = UUID.randomUUID().toString(), senderUid = myUid,
            messageType = MessageType.TASK_REF.code, timestamp = System.currentTimeMillis(),
            body = TaskRefBody(task.taskId, task.title, taskStatusLabel(task.status)))
    }
    sharingTo = chatId
    shareError = null
    scope.launch {
        try {
            localData.run { session.enqueueOutgoing(message) }
            failedShareMessages.remove(key)
            sharing = null
            notice = "任务引用已加入发送队列"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { shareError = "分享未能加入发送队列，请重试" }
        finally { sharingTo = null }
    }
}
