package com.virjar.tk.env

import com.virjar.tk.client.JvmPrivateDataDirectory
import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.util.Base64

internal data class DesktopPreparedDataDirectory(
    val dataDirectory: File,
    val legacySourceDirectory: File?,
)

/** One-time, fail-closed copy from the former installation-relative `data` directory. */
internal object DesktopDataMigration {
    fun prepare(plan: DesktopDataDirectoryPlan): DesktopPreparedDataDirectory {
        DesktopDataDirectoryPolicy.prepareBaseDirectory(plan)
        val target = plan.dataDirectory.toPath().toAbsolutePath().normalize()
        val stage = target.resolveSibling(".${target.fileName}.migrating-v1")
        val legacy = plan.legacyInstallationDataDirectory
            ?.toPath()
            ?.toAbsolutePath()
            ?.normalize()

        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
                "Desktop app-data root and a migration stage both exist; refusing an ambiguous recovery"
            }
            val data = openRecognized(target, plan.ownerAnchor)
            if (!legacyHasPayload(legacy)) {
                return DesktopPreparedDataDirectory(data.root.toFile(), null)
            }
            checkNotNull(legacy)
            requireMigrationReceipt(data, legacy)
            validateUnchangedPrivateLegacy(plan, legacy)
            return DesktopPreparedDataDirectory(data.root.toFile(), legacy.toFile())
        }

        if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
            checkNotNull(legacy) { "Unrecognized Desktop data migration stage" }
            recoverCompleteStage(plan, target, stage, legacy)
            val data = openRecognized(target, plan.ownerAnchor)
            return DesktopPreparedDataDirectory(data.root.toFile(), legacy.toFile())
        }

        if (!legacyHasPayload(legacy)) {
            val data = JvmPrivateDataDirectory.openOrCreate(plan.dataDirectory, plan.ownerAnchor)
            installOrValidateMarker(data)
            return DesktopPreparedDataDirectory(data.root.toFile(), null)
        }

        checkNotNull(legacy)
        copyLegacy(plan, target, stage, legacy)
        val data = openRecognized(target, plan.ownerAnchor)
        return DesktopPreparedDataDirectory(data.root.toFile(), legacy.toFile())
    }

    private fun copyLegacy(
        plan: DesktopDataDirectoryPlan,
        target: Path,
        stage: Path,
        legacy: Path,
    ) {
        rejectLexicalSymlink(legacy, "Legacy Desktop data root")
        val legacyData = openPrivateLegacy(plan, legacy)
        LegacyDataLock.acquire(legacyData).use {
            require(legacyHasPayload(legacy)) { "Legacy Desktop data disappeared during migration" }
            legacyData.validateTrustedLegacyTree(legacy.toFile())
            val stagingData = JvmPrivateDataDirectory.createNew(stage.toFile(), plan.ownerAnchor)
            try {
                stagingData.copyTrustedLegacyTreeFrom(
                    sourceDirectory = legacy.toFile(),
                    ignoredRootNames = MIGRATION_IGNORED_ROOT_NAMES,
                )
                stagingData.atomicTextFile(fileName = DATA_MARKER_FILE).replaceText(DATA_MARKER_CONTENT)
                stagingData.atomicTextFile(fileName = MIGRATION_RECEIPT_FILE).replaceText(
                    migrationReceipt(legacy),
                )
                legacyData.validateTrustedLegacyTree(legacy.toFile())
                stagingData.validatePrivateTree()
                moveAtomically(stage, target)
            } catch (failure: Throwable) {
                safelyDeleteCreatedStage(stagingData)
                throw failure
            }
        }
    }

    /** A complete stage can remain only if the final same-filesystem atomic rename was interrupted. */
    private fun recoverCompleteStage(
        plan: DesktopDataDirectoryPlan,
        target: Path,
        stage: Path,
        legacy: Path,
    ) {
        require(legacyHasPayload(legacy)) {
            "Incomplete Desktop data migration has no matching legacy source"
        }
        val stagingData = openCompleteStage(stage, plan.ownerAnchor)
        requireMigrationReceipt(stagingData, legacy)
        val legacyData = openPrivateLegacy(plan, legacy)
        LegacyDataLock.acquire(legacyData).use {
            legacyData.validateTrustedLegacyTree(legacy.toFile())
            stagingData.validatePrivateTree()
            moveAtomically(stage, target)
        }
    }

    private fun validateUnchangedPrivateLegacy(plan: DesktopDataDirectoryPlan, legacy: Path) {
        rejectLexicalSymlink(legacy, "Legacy Desktop data root")
        val legacyData = openPrivateLegacy(plan, legacy)
        LegacyDataLock.acquire(legacyData).use {
            legacyData.validateTrustedLegacyTree(legacy.toFile())
        }
    }

    private fun openPrivateLegacy(
        plan: DesktopDataDirectoryPlan,
        legacy: Path,
    ): JvmPrivateDataDirectory = try {
        DesktopDataDirectoryPolicy.validateLegacyParentChain(plan, legacy.toFile())
        JvmPrivateDataDirectory.openExisting(legacy.toFile(), plan.currentUserAnchor)
    } catch (failure: Throwable) {
        throw IllegalStateException(
            "Old installation data is not entirely owner-only, or its parent chain is replaceable " +
                "(POSIX 0700/0600 or Windows ACL); " +
                "migrate it manually instead of allowing TeamTalk to copy credentials from it",
            failure,
        )
    }

    private fun openRecognized(path: Path, ownerAnchor: File): JvmPrivateDataDirectory {
        val data = JvmPrivateDataDirectory.openExisting(path.toFile(), ownerAnchor)
        installOrValidateMarker(data)
        return data
    }

    /** Recovery is validation-only: an incomplete or foreign stage is never adopted or rewritten. */
    private fun openCompleteStage(path: Path, ownerAnchor: File): JvmPrivateDataDirectory {
        val data = JvmPrivateDataDirectory.openExisting(path.toFile(), ownerAnchor)
        require(data.atomicTextFile(fileName = DATA_MARKER_FILE).readText(256) == DATA_MARKER_CONTENT) {
            "Incomplete or unrecognized Desktop data migration stage"
        }
        return data
    }

    private fun installOrValidateMarker(data: JvmPrivateDataDirectory) {
        val marker = data.atomicTextFile(fileName = DATA_MARKER_FILE)
        val existing = marker.readText(256)
        if (existing == null) {
            require(data.isEmpty()) {
                "Existing Desktop app-data directory is unmarked and non-empty; refusing to adopt it"
            }
            marker.replaceText(DATA_MARKER_CONTENT)
        } else {
            require(existing == DATA_MARKER_CONTENT) { "Unknown Desktop app-data marker" }
        }
    }

    private fun requireMigrationReceipt(data: JvmPrivateDataDirectory, legacy: Path) {
        val receipt = data.atomicTextFile(fileName = MIGRATION_RECEIPT_FILE).readText(4096)
        require(receipt == migrationReceipt(legacy)) {
            "Both Desktop data roots contain data without a matching migration receipt; " +
                "TeamTalk refuses to choose an account implicitly"
        }
    }

    private fun legacyHasPayload(path: Path?): Boolean {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false
        val attributes = attributes(path)
        require(attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther) {
            "Legacy Desktop data root must be a real directory"
        }
        return Files.newDirectoryStream(path).use { children ->
            children.any { it.fileName.toString() !in MIGRATION_IGNORED_ROOT_NAMES }
        }
    }

    private fun safelyDeleteCreatedStage(stage: JvmPrivateDataDirectory) {
        runCatching {
            val paths = Files.walk(stage.root).use { stream ->
                stream.sorted(Comparator.comparingInt<Path> { it.nameCount }.reversed()).toList()
            }
            paths.forEach { path ->
                if (path != stage.root) {
                    val relative = stage.root.relativize(path)
                    val node = attributes(path)
                    if (node.isDirectory && !node.isSymbolicLink && !node.isOther) {
                        stage.ensureDirectory(*relative.map { it.toString() }.toTypedArray())
                    } else {
                        require(node.isRegularFile && !node.isSymbolicLink && !node.isOther)
                        val components = relative.map { it.toString() }
                        stage.requirePrivateFile(components.dropLast(1), components.last())
                    }
                    Files.delete(path)
                }
            }
            require(stage.isEmpty()) { "Migration stage cleanup order is invalid" }
            Files.delete(stage.root)
        }
    }

    private fun migrationReceipt(legacy: Path): String = buildString {
        appendLine("teamtalk-desktop-install-data-copy-v1")
        append("source=")
        appendLine(
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                legacy.toAbsolutePath().normalize().toString().encodeToByteArray(),
            ),
        )
    }

    private fun rejectLexicalSymlink(path: Path, label: String) {
        require(path.toRealPath() == path.toAbsolutePath().normalize()) { "$label cannot traverse a symbolic link" }
    }

    private fun moveAtomically(source: Path, target: Path) {
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Migration target already exists: $target" }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            if (Files.getFileAttributeView(
                    requireNotNull(target.parent),
                    PosixFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ) != null
            ) {
                FileChannel.open(requireNotNull(target.parent), StandardOpenOption.READ).use { it.force(true) }
            }
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IllegalStateException("Desktop data migration requires an atomic same-filesystem rename", unsupported)
        }
    }

    private fun attributes(path: Path): BasicFileAttributes = Files.readAttributes(
        path,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    )

    private const val DATA_MARKER_FILE = ".teamtalk-desktop-data"
    private const val DATA_MARKER_CONTENT = "teamtalk-desktop-data-v1\n"
    private const val MIGRATION_RECEIPT_FILE = ".installation-data-migration"
    private val MIGRATION_IGNORED_ROOT_NAMES = setOf(".lock", DATA_MARKER_FILE, MIGRATION_RECEIPT_FILE)
}

/** Uses the old app's existing lock without creating, writing or chmodding anything in the source. */
private class LegacyDataLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : Closeable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        fun acquire(data: JvmPrivateDataDirectory): LegacyDataLock {
            val lockPath = data.root.resolve(".lock")
            require(Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                "Old Desktop data has no existing process lock; migrate it manually"
            }
            data.requirePrivateFile(emptyList(), ".lock")
            val options: Set<OpenOption> = setOf(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            val channel = FileChannel.open(lockPath, options)
            val lock = try {
                channel.tryLock()
            } catch (failure: Throwable) {
                channel.close()
                throw IllegalStateException("Old Desktop data is in use by another process", failure)
            }
            if (lock == null) {
                channel.close()
                error("Old Desktop data is in use by another process")
            }
            return LegacyDataLock(channel, lock)
        }
    }
}
