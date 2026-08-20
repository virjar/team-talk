package com.virjar.tk.e2e

import com.virjar.tk.bot.ImBotCacheOwner
import com.virjar.tk.testing.FakeLocalCache

/** Server E2E bots are process-local fixtures; production headless callers use a persistent owner. */
internal val testImBotCacheOwner = ImBotCacheOwner { FakeLocalCache() }
