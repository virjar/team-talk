package com.virjar.tk.app.ui

/**
 * 已渲染 UI owner 与会话级业务工作之间的同步准入边界。
 *
 * 当平台开始退役其已认证的展示时，Native/Compose 输入可能已经排队。因此每个处理器都必须在
 * 执行时重新检查准入。实现方将 [runIfOpen] 与 close 线性化：先胜出的操作在退役继续之前
 * 同步完成，而落败的旧排队处理器则是无害的空操作。
 *
 * 草稿最后一帧捕获属于生命周期操作而非用户输入，刻意使用其专用 bridge 而不走此边界。
 */
fun interface UiActionAdmission {
    fun runIfOpen(action: () -> Unit): Boolean

    fun guard(action: () -> Unit): () -> Unit = {
        runIfOpen(action)
    }

    fun <A> guard(action: (A) -> Unit): (A) -> Unit = { first ->
        runIfOpen { action(first) }
    }

    fun <A, B> guard(action: (A, B) -> Unit): (A, B) -> Unit = { first, second ->
        runIfOpen { action(first, second) }
    }

    fun <A, B, C> guard(action: (A, B, C) -> Unit): (A, B, C) -> Unit = { first, second, third ->
        runIfOpen { action(first, second, third) }
    }

    fun <A, B, C, D> guard(
        action: (A, B, C, D) -> Unit,
    ): (A, B, C, D) -> Unit = { first, second, third, fourth ->
        runIfOpen { action(first, second, third, fourth) }
    }
}

/** 仅由没有可退役的已认证 owner 的预览/测试使用。 */
val AlwaysOpenUiActionAdmission = UiActionAdmission { action ->
    action()
    true
}
