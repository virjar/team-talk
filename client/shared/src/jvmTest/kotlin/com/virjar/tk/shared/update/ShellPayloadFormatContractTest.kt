package com.virjar.tk.shared.update

import com.virjar.tk.desktop.shell.PayloadStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 壳端（desktop-bootstrap/PayloadStore）与更新器（shared/PayloadLayout）对同一份磁盘格式
 * 各自实现的互操作契约：描述符、当前指针与目录摘要必须双向可读且语义一致。
 *
 * bootstrap 刻意零依赖，两套实现只能靠本契约锁住格式——任何一侧改动字段、摘要顺序或
 * 属性名都会在此失败，而不是在用户升级后静默破坏启动/更新。
 */
class ShellPayloadFormatContractTest {
    private val temp = Files.createTempDirectory("payload-contract-").toFile()

    private fun descriptorOf(channel: String? = null) = PayloadLayout.PayloadDescriptor(
        version = "0.0.5",
        build = 226L,
        minShellAbi = 3,
        files = listOf(
            PayloadLayout.PayloadFile("lib/app.jar", "a".repeat(64), 1024L),
            PayloadLayout.PayloadFile("lib/dep.jar", "b".repeat(64), 2048L),
        ),
        buildIdentity = "0.0.5+abcdef0123456789",
        channel = channel ?: "stable",
    )

    @Test
    fun `updater written descriptor is readable by the shell`() {
        val dir = Files.createTempDirectory(temp.toPath(), "ver-").toFile()
        PayloadLayout.writeDescriptor(dir, descriptorOf(channel = "snapshot"))

        val shell = assertNotNull(PayloadStore.readDescriptor(dir))
        assertEquals("0.0.5", shell.version)
        assertEquals(226L, shell.build)
        assertEquals(3, shell.minShellAbi)
        assertEquals("0.0.5+abcdef0123456789", shell.buildIdentity)
        assertEquals(listOf("lib/app.jar", "lib/dep.jar"), shell.files.map { it.path })
        assertEquals(listOf(1024L, 2048L), shell.files.map { it.size })
        assertEquals(listOf("a".repeat(64), "b".repeat(64)), shell.files.map { it.sha256 })
    }

    @Test
    fun `shell written seed descriptor is readable by the updater`() {
        val dir = Files.createTempDirectory(temp.toPath(), "seed-").toFile()
        val shellDescriptor = PayloadStore.Descriptor(
            version = "0.0.6",
            build = 230L,
            minShellAbi = null,
            files = listOf(PayloadStore.PayloadFile("app.jar", "c".repeat(64), 7L)),
            buildIdentity = null,
        )
        val props = java.util.Properties().apply {
            setProperty("version", shellDescriptor.version)
            setProperty("build", shellDescriptor.build.toString())
            setProperty("files.count", shellDescriptor.files.size.toString())
            shellDescriptor.files.forEachIndexed { index, file ->
                setProperty("file.$index.path", file.path)
                setProperty("file.$index.sha256", file.sha256)
                setProperty("file.$index.size", file.size.toString())
            }
        }
        File(dir, "payload.properties").outputStream().use { props.store(it, "contract") }

        val updater = assertNotNull(PayloadLayout.readDescriptor(dir))
        assertEquals("0.0.6", updater.version)
        assertEquals(230L, updater.build)
        assertNull(updater.minShellAbi)
        assertNull(updater.buildIdentity)
        assertEquals("stable", updater.channel, "缺省通道必须与更新器默认一致")
        assertEquals(shellDescriptor.files.map { it.path }, updater.files.map { it.path })
    }

    @Test
    fun `current pointer round trips in both directions`() {
        val root = Files.createTempDirectory(temp.toPath(), "root-").toFile()

        // 更新器写指针 → 壳端读（更新落位后由壳启动）。
        PayloadLayout.writeCurrentPointer(
            root,
            PayloadLayout.CurrentPointer("0.0.5", 226L, "abc123.-dir", seedId = "seed-sha"),
        )
        val shellPointer = assertNotNull(PayloadStore.readPointer(root))
        assertEquals("0.0.5", shellPointer.version)
        assertEquals(226L, shellPointer.build)
        assertEquals("abc123.-dir", shellPointer.directory)
        assertEquals("seed-sha", shellPointer.seedId)

        // 壳端写指针（种子安装）→ 更新器读（保留 seedId 语义）。
        val shellRoot = Files.createTempDirectory(temp.toPath(), "root2-").toFile()
        PayloadStore.writePointer(
            shellRoot,
            PayloadStore.Pointer("0.0.6", 230L, "def456.dir", seedId = "seed-sha-2"),
        )
        val updaterPointer = assertNotNull(PayloadLayout.readCurrentPointer(shellRoot))
        assertEquals("0.0.6", updaterPointer.version)
        assertEquals(230L, updaterPointer.build)
        assertEquals("def456.dir", updaterPointer.directory)
        assertEquals("seed-sha-2", updaterPointer.seedId)
    }

    @Test
    fun `directory digest is identical between shell and updater`() {
        // 目录名摘要决定同内容是否复用同一目录：两侧算法漂移会产生重复目录与种子误判。
        val updaterDescriptor = descriptorOf()
        val shellDescriptor = PayloadStore.Descriptor(
            version = updaterDescriptor.version,
            build = updaterDescriptor.build,
            minShellAbi = updaterDescriptor.minShellAbi,
            files = updaterDescriptor.files.map { PayloadStore.PayloadFile(it.path, it.sha256, it.size) },
            buildIdentity = updaterDescriptor.buildIdentity,
        )
        assertEquals(PayloadStore.directoryName(shellDescriptor), PayloadLayout.directoryName(updaterDescriptor))

        // buildIdentity 缺省（null）与显式空串必须同摘要：壳端写入 orEmpty()。
        val withoutIdentity = PayloadStore.Descriptor(
            version = updaterDescriptor.version,
            build = updaterDescriptor.build,
            minShellAbi = updaterDescriptor.minShellAbi,
            files = shellDescriptor.files,
            buildIdentity = null,
        )
        val updaterWithout = PayloadLayout.PayloadDescriptor(
            version = updaterDescriptor.version,
            build = updaterDescriptor.build,
            minShellAbi = updaterDescriptor.minShellAbi,
            files = updaterDescriptor.files,
            buildIdentity = "",
        )
        assertEquals(PayloadStore.directoryName(withoutIdentity), PayloadLayout.directoryName(updaterWithout))
    }
}
