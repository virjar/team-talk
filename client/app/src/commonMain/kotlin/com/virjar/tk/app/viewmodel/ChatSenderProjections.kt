package com.virjar.tk.app.viewmodel

import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User
import com.virjar.tk.shared.client.LocalCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * 驻留消息窗口的发送者订阅和内存投影由同一对象拥有。
 *
 * 每个 uid 至多订阅一次，离开窗口即释放。订阅 Job 本身就是不可复用的身份，
 * 无需再维护一套 generation。校验订阅、发布用户和清空窗口在同一把锁内完成，
 * 防止已驱逐或已关闭的收集器在检查通过后把用户重新放回投影。
 */
internal class ChatSenderProjections(
    private val scope: CoroutineScope,
    private val observeUser: (String) -> Flow<User?>,
) {
    private val lock = Any()
    private var open = true
    private val subscriptions = linkedMapOf<String, Job>()
    private val residentUsers = MutableStateFlow<Map<String, User>>(emptyMap())
    val users = residentUsers.asStateFlow()

    fun bind(messages: List<Message>) {
        val required = messages.asSequence()
            .take(LocalCache.DEFAULT_MESSAGE_WINDOW)
            .map(Message::senderUid)
            .filter(String::isNotBlank)
            .toCollection(linkedSetOf())
        val cancelled = mutableListOf<Job>()
        val started = mutableListOf<Job>()
        synchronized(lock) {
            if (!open) return
            subscriptions.keys.toList().forEach { uid ->
                if (uid !in required) subscriptions.remove(uid)?.let(cancelled::add)
            }
            required.forEach { uid ->
                if (uid in subscriptions) return@forEach
                // LAZY 使订阅先登记再运行，即使调用者使用即时 dispatcher 也成立。
                val subscription = scope.launch(start = CoroutineStart.LAZY) {
                    val owner = currentCoroutineContext().job
                    observeUser(uid).collect { user ->
                        synchronized(lock) {
                            if (open && subscriptions[uid] === owner) {
                                residentUsers.value = if (user == null) {
                                    residentUsers.value - uid
                                } else {
                                    residentUsers.value + (uid to user)
                                }
                            }
                        }
                    }
                }
                subscriptions[uid] = subscription
                started += subscription
            }
            // 先完整登记，再发布；即时状态观察者即使重入 close，也能取消全部候选订阅。
            residentUsers.value = residentUsers.value.filterKeys(required::contains)
        }
        cancelled.forEach(Job::cancel)
        started.forEach(Job::start)
    }

    fun close() {
        val cancelled = synchronized(lock) {
            if (!open) return
            open = false
            residentUsers.value = emptyMap()
            subscriptions.values.toList().also { subscriptions.clear() }
        }
        cancelled.forEach(Job::cancel)
    }
}
