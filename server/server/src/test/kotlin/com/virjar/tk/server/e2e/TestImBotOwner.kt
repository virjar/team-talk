package com.virjar.tk.server.e2e

import com.virjar.tk.shared.bot.ImBotCacheOwner
import com.virjar.tk.shared.testkit.FakeLocalCache

internal const val TEST_IM_BOT_PASSWORD = "test-only-im-bot-password"

/** 服务端 E2E bot 是进程内测试夹具；生产 headless 调用方使用持久化的 owner。 */
internal val testImBotCacheOwner = ImBotCacheOwner { _, datasetId, _ ->
    FakeLocalCache(initialDatasetId = datasetId)
}
