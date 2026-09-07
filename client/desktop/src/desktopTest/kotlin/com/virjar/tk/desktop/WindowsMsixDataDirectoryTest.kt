package com.virjar.tk.desktop

import com.virjar.tk.desktop.env.WindowsMsixDataDirectory
import com.virjar.tk.desktop.env.DesktopDataDirectoryInputs
import com.virjar.tk.desktop.env.DesktopDataDirectoryPolicy
import com.virjar.tk.desktop.env.DesktopHostPlatform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsMsixDataDirectoryTest {
    @Test
    fun `packaged startup checks real LocalAppData instead of nesting Packages below redirected environment`() = withLocalAppData { base ->
        val cache = base.resolve("Packages/Teamtalk_fixture/LocalCache/Local")
        val old = Files.createDirectories(cache.resolve("TeamTalk"))
        Files.writeString(old.resolve(".teamtalk-desktop-data"), "teamtalk-desktop-data-v1\n")
        val plan = defaultPlan(base, cache)

        val failure = assertFailsWith<IllegalStateException> {
            WindowsMsixDataDirectory.checkBeforeOpening(
                plan, DesktopHostPlatform.WINDOWS, { "Teamtalk_fixture" }, { base },
            )
        }

        assertTrue(failure.message.orEmpty().contains("旧目录：$old"))
        assertTrue(failure.message.orEmpty().contains("当前目录：${plan.dataDirectory}"))
        assertFalse(Files.exists(cache.resolve("Packages")))
        assertEquals("teamtalk-desktop-data-v1\n", Files.readString(old.resolve(".teamtalk-desktop-data")))
    }

    @Test
    fun `explicit profiles other platforms and unpackaged processes do not query Windows folders`() = withLocalAppData { base ->
        val plan = defaultPlan(base, base)
        val unexpectedIdentity = { error("Package identity must not be queried") }
        val unexpectedFolder = { error("Windows folders must not be queried") }

        WindowsMsixDataDirectory.checkBeforeOpening(
            plan.copy(isExplicitOverride = true), DesktopHostPlatform.WINDOWS, unexpectedIdentity, unexpectedFolder,
        )
        WindowsMsixDataDirectory.checkBeforeOpening(
            plan, DesktopHostPlatform.MACOS, unexpectedIdentity, unexpectedFolder,
        )
        WindowsMsixDataDirectory.checkBeforeOpening(
            plan, DesktopHostPlatform.WINDOWS, { null }, unexpectedFolder,
        )
        assertFalse(Files.exists(plan.dataDirectory.toPath()))
    }

    @Test
    fun `conflicting default environment stops without opening a different data root`() = withLocalAppData { base ->
        val configured = base.resolve("different-local-app-data")
        val plan = defaultPlan(base, configured)

        val failure = assertFailsWith<IllegalStateException> {
            WindowsMsixDataDirectory.checkBeforeOpening(
                plan, DesktopHostPlatform.WINDOWS, { "Teamtalk_fixture" }, { base },
            )
        }

        assertTrue(failure.message.orEmpty().contains("当前目录：${configured.resolve("TeamTalk")}"))
        assertTrue(failure.message.orEmpty().contains("系统目录：${base.resolve("TeamTalk")}"))
        assertFalse(Files.exists(configured))
        assertFalse(Files.exists(base.resolve("TeamTalk")))
    }

    @Test
    fun `legacy overlay blocks fresh login without touching either copy of account data`() = withLocalAppData { base ->
        val old = Files.createDirectories(base.resolve("Packages/Teamtalk_fixture/LocalCache/Local/TeamTalk"))
        Files.writeString(old.resolve("auth.properties"), "old-account")
        val fresh = base.resolve("TeamTalk")
        val failure = assertFailsWith<IllegalStateException> {
            WindowsMsixDataDirectory.requireNoLegacyData(base, "TeamTalk", "Teamtalk_fixture")
        }
        assertTrue(failure.message.orEmpty().contains(old.toString()))
        assertTrue(failure.message.orEmpty().contains(fresh.toString()))
        assertFalse(Files.exists(fresh))
        Files.createDirectories(fresh)
        Files.writeString(fresh.resolve("auth.properties"), "another-account")
        assertFailsWith<IllegalStateException> {
            WindowsMsixDataDirectory.requireNoLegacyData(base, "TeamTalk", "Teamtalk_fixture")
        }
        assertEquals("old-account", Files.readString(old.resolve("auth.properties")))
        assertEquals("another-account", Files.readString(fresh.resolve("auth.properties")))
    }

    @Test
    fun `new install and unpackaged startup do not adopt other distribution data`() = withLocalAppData { base ->
        val old = Files.createDirectories(base.resolve("Packages/Other_fixture/LocalCache/Local/TeamTalk"))
        Files.writeString(old.resolve("auth.properties"), "other-distribution")
        WindowsMsixDataDirectory.requireNoLegacyData(base, "TeamTalk", "Teamtalk_fixture")
        WindowsMsixDataDirectory.requireNoLegacyData(base, "TeamTalk", null)
        Files.createDirectories(base.resolve("Packages/Teamtalk_fixture/LocalCache/Local/TeamTalk"))
        WindowsMsixDataDirectory.requireNoLegacyData(base, "TeamTalk", "Teamtalk_fixture")
        assertFalse(Files.exists(base.resolve("TeamTalk")))
        assertEquals("other-distribution", Files.readString(old.resolve("auth.properties")))
    }

    private fun withLocalAppData(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("teamtalk-msix-data-")
        try { block(directory) } finally { directory.toFile().deleteRecursively() }
    }

    private fun defaultPlan(home: Path, localAppData: Path) = DesktopDataDirectoryPolicy.resolve(
        DesktopDataDirectoryInputs(
            osName = "Windows 10",
            userHome = home.toFile(),
            environment = mapOf("LOCALAPPDATA" to localAppData.toString()),
            explicitDataDirectory = null,
            dataDirectoryName = "TeamTalk",
        ),
    )
}
