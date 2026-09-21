package com.virjar.tk.server.infra.db

import org.jetbrains.exposed.sql.Table

/**
 * 客户端发布注册中心（统一版本管理）的三张表。
 *
 * 一个 release 行代表某 clientType+platform+arch 在某 version+build 的发布；
 * 文件内容不落 DB，统一进 ReleaseStore 内容寻址仓，[ClientReleaseFiles] 只记索引。
 * 通道（stable/preview/snapshot）是独立的指针表，停用/回滚只改指针或状态，
 * 不改发布行本身，保证 stable/preview 发布不可变。
 */
internal object ClientReleases : Table("client_release") {
    val id = long("id").autoIncrement()

    /** desktop | android | ios | headless。 */
    val clientType = varchar("client_type", 16)

    /** macos | windows | linux | android | any。 */
    val platform = varchar("platform", 16)

    /** amd64 | aarch64 | any。 */
    val arch = varchar("arch", 16)

    val version = varchar("version", 32)
    val build = long("build")
    val buildIdentity = varchar("build_identity", 128).default("")
    val uploadSha256 = varchar("upload_sha256", 64).nullable()

    /** 上传时声明的通道；仅作历史记录，实际生效以 ClientChannels 指针为准。 */
    val channel = varchar("channel", 16)

    val notesZh = text("notes_zh").nullable()
    val forced = bool("forced").default(false)

    /** 负载要求的最低壳 ABI；壳低于该值时客户端必须换新首装包（全量）。 */
    val minShellAbi = integer("min_shell_abi").nullable()

    /** 该发布配套的壳摘要（构建侧填），仅诊断展示用。 */
    val shellDigest = varchar("shell_digest", 128).nullable()

    /** ACTIVE 可被通道指向；DISABLED 运维停用；SUPERSEDED 仅为历史状态保留。 */
    val status = varchar("status", 16)

    val fileCount = integer("file_count").default(0)
    val totalBytes = long("total_bytes").default(0)
    val createdAt = long("created_at")
    val createdBy = varchar("created_by", 64)

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_client_release_identity", clientType, platform, arch, version, build, buildIdentity)
        index("idx_client_release_target", false, clientType, platform, arch, status)
    }
}

/**
 * 发布制品索引。payload = 应用内增量更新的文件（含负载内相对路径）；
 * bundle = 全量负载压缩包（payload.zip / headless zip）；installer = 首装包。
 */
internal object ClientReleaseFiles : Table("client_release_file") {
    val releaseId = long("release_id")
    val kind = varchar("kind", 16)
    val path = varchar("path", 500)
    val sha256 = varchar("sha256", 64)
    val size = long("size")

    /** POSIX mode（可执行位等），仅 payload 有意义；null = 平台默认。 */
    val mode = integer("mode").nullable()

    /** installer/bundle 的展示名（如 “Windows 安装包”）。 */
    val label = varchar("label", 100).nullable()

    override val primaryKey = PrimaryKey(releaseId, kind, path)

    init {
        index("idx_client_release_file_sha", false, sha256)
    }
}

/** 通道指针：当前生效发布 + 运维 kill-switch。 */
internal object ClientChannels : Table("client_channel") {
    val clientType = varchar("client_type", 16)
    val platform = varchar("platform", 16)
    val arch = varchar("arch", 16)
    val channel = varchar("channel", 16)

    /** null = 该通道从未发布或已被清空。 */
    val currentReleaseId = long("current_release_id").nullable()

    /** false = 禁用该端点（客户端会收到 CHANNEL_DISABLED，不提示不更新）。 */
    val enabled = bool("enabled").default(true)

    val updatedBy = varchar("updated_by", 64)
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(clientType, platform, arch, channel)
}
