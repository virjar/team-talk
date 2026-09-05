package deployment

import java.io.File
import org.gradle.api.GradleException

private val serverDataEpochDeclaration = Regex("""const\s+val\s+CURRENT_EPOCH\s*=\s*(\d+)""")
private val canonicalDatasetId = Regex(
    "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
)

/** 校验远程 POSIX 路径，但不套用宿主机操作系统的路径语义。 */
internal fun requireCanonicalDeployPath(value: String): String {
    require(value.startsWith('/') && value != "/") {
        "deployPath must be an absolute non-root path"
    }
    val segments = value.drop(1).split('/')
    require(segments.all { segment ->
        segment.isNotEmpty() &&
            segment != "." &&
            segment != ".." &&
            segment.all { character ->
                character in 'A'..'Z' ||
                    character in 'a'..'z' ||
                    character in '0'..'9' ||
                    character == '.' ||
                    character == '_' ||
                    character == '-'
            }
    }) {
        "deployPath must be canonical and contain only safe path segments"
    }
    require("/${segments.joinToString("/")}" == value) {
        "deployPath must already be in canonical form"
    }
    return value
}

internal fun parseServerDataEpoch(source: String): Int {
    val epoch = serverDataEpochDeclaration.find(source)?.groupValues?.get(1)?.toIntOrNull()
    // 合并阶段将 epoch 计数器重置为 0，这是合法值；
    // 只有声明缺失或无法解析才会失败。
    require(epoch != null && epoch >= 0) { "Cannot determine ServerDataEpoch.CURRENT_EPOCH" }
    return epoch
}

internal fun readServerDataEpoch(rootDir: File): Int {
    val source = File(
        rootDir,
        "server/server/src/main/kotlin/com/virjar/tk/server/infra/ServerDataEpoch.kt",
    )
    if (!source.isFile) throw GradleException("Server data epoch source not found: $source")
    return try {
        parseServerDataEpoch(source.readText())
    } catch (failure: IllegalArgumentException) {
        throw GradleException("Cannot determine the server data epoch before deployment", failure)
    }
}

internal fun dataEpochReadCommand(deployPath: String): String {
    requireCanonicalDeployPath(deployPath)
    return "test -f $deployPath/data/data-epoch && tr -d '[:space:]' < $deployPath/data/data-epoch"
}

internal fun schemaEpochReadCommand(deployPath: String): String {
    requireCanonicalDeployPath(deployPath)
    return "cd $deployPath && ${dockerComposeCmd()} exec -T postgres " +
        "psql -U teamtalk -d teamtalk -Atqc \"SELECT epoch FROM schema_metadata WHERE id = 1\" 2>/dev/null"
}

internal fun dataDatasetIdReadCommand(deployPath: String): String {
    requireCanonicalDeployPath(deployPath)
    return "test -f $deployPath/data/dataset-id && " +
        "cat $deployPath/data/dataset-id"
}

internal fun schemaDatasetIdReadCommand(deployPath: String): String {
    requireCanonicalDeployPath(deployPath)
    return "cd $deployPath && ${dockerComposeCmd()} exec -T postgres " +
        "psql -U teamtalk -d teamtalk -Atqc \"SELECT dataset_id FROM schema_metadata WHERE id = 1\" " +
        "2>/dev/null"
}

internal fun upgradeEpochRejectionMessage(
    requiredEpoch: Int,
    dataEpochText: String?,
    schemaEpochText: String?,
    deployPath: String,
): String? {
    require(requiredEpoch >= 0)
    requireCanonicalDeployPath(deployPath)
    val dataEpoch = dataEpochText?.trim()?.toIntOrNull()
    val schemaEpoch = schemaEpochText?.trim()?.toIntOrNull()
    if (dataEpoch == requiredEpoch && schemaEpoch == requiredEpoch) return null

    return "Upgrade blocked before stopping or overwriting TeamTalk: required epoch=$requiredEpoch, " +
        "data marker=${dataEpochText?.trim().orEmpty().ifEmpty { "missing/unreadable" }}, " +
        "PostgreSQL schema=${schemaEpochText?.trim().orEmpty().ifEmpty { "missing/unreadable" }}. " +
        "Restore PostgreSQL availability or provide a reviewed migration for both PostgreSQL and " +
        "$deployPath/data before retrying. Destructive reset requires explicit authorization; " +
        "The preflight did not delete any data."
}

internal fun datasetIdentityRejectionMessage(
    dataDatasetIdText: String?,
    schemaDatasetIdText: String?,
    deployPath: String,
): String? {
    requireCanonicalDeployPath(deployPath)
    val dataDatasetId = dataDatasetIdText?.trim()?.takeIf(canonicalDatasetId::matches)
    val schemaDatasetId = schemaDatasetIdText?.trim()?.takeIf(canonicalDatasetId::matches)
    if (dataDatasetId != null && dataDatasetId == schemaDatasetId) return null

    val condition = when {
        dataDatasetId == null -> "local dataset marker is missing or invalid"
        schemaDatasetId == null -> "PostgreSQL dataset identity is missing or invalid"
        else -> "PostgreSQL and local durable data belong to different datasets"
    }
    return "Upgrade blocked before stopping or overwriting TeamTalk: $condition. " +
        "Restore PostgreSQL and $deployPath/data from the same stopped-server dataset, then " +
        "rerun deployServer. The preflight did not delete or rewrite any data."
}

internal fun preflightUpgradeEpoch(
    host: String,
    user: String,
    port: Int,
    deployPath: String,
    requiredEpoch: Int,
) {
    val dataEpoch = remoteCaptureProbe(
        "read remote file-store data epoch",
        host,
        user,
        dataEpochReadCommand(deployPath),
        port,
    )
    val schemaEpoch = remoteCaptureProbe(
        "read remote PostgreSQL schema epoch",
        host,
        user,
        schemaEpochReadCommand(deployPath),
        port,
    )
    upgradeEpochRejectionMessage(requiredEpoch, dataEpoch, schemaEpoch, deployPath)?.let {
        throw GradleException(it)
    }
    val dataDatasetId = remoteCaptureProbe(
        "read remote local dataset identity",
        host,
        user,
        dataDatasetIdReadCommand(deployPath),
        port,
    )
    val schemaDatasetId = remoteCaptureProbe(
        "read remote PostgreSQL dataset identity",
        host,
        user,
        schemaDatasetIdReadCommand(deployPath),
        port,
    )
    datasetIdentityRejectionMessage(dataDatasetId, schemaDatasetId, deployPath)?.let {
        throw GradleException(it)
    }
    println("  Epoch and dataset preflight passed (schema/data epoch $requiredEpoch)")
}

internal fun upgradeRsyncArguments(
    distDir: File,
    user: String,
    host: String,
    port: Int,
    deployPath: String,
): List<String> {
    requireCanonicalDeployPath(deployPath)
    return buildList {
        addAll(
            listOf(
                "rsync", "-avz", "--no-owner", "--no-group", "--delete",
                "--partial-dir=$STAGED_UPLOAD_PARTIAL_DIRECTORY",
                "--exclude=/data/", "--exclude=/logs/",
                "--exclude=/docker-compose.yml", "--exclude=/.pid",
                "--exclude=/conf/ssl/", "--exclude=/conf/env.sh",
                "--exclude=/static/downloads/",
            ),
        )
        addAll(remoteRsyncTransportArguments(host, user, port))
        add("${distDir.absolutePath}/")
        add("$user@$host:$deployPath/")
    }
}
