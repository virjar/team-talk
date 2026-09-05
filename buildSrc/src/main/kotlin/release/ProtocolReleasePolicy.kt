package release

import java.io.File
import java.security.MessageDigest
import java.util.Properties

/** A committed release record reserves its protocol contract before any artifact is distributed. */
data class ProtocolReleaseSnapshot(
    val releaseVersion: String,
    val releaseBuildNumber: Int,
    val protocolMajor: Int,
    val protocolMinor: Int,
    val minimumProtocolMinor: Int,
    val wireBaseline: File,
)

/** Local release history is authoritative for private distributions too; GitHub tags are not required. */
object ProtocolReleasePolicy {
    private const val RELEASES = "protocol/protocol/releases"
    private const val DEVELOPMENT_BASELINE = "protocol/protocol/wire-baseline.tsv"

    fun latest(rootDir: File): ProtocolReleaseSnapshot = history(rootDir).last()

    fun verify(
        rootDir: File,
        releaseVersion: String,
        releaseBuildNumber: Int,
        protocolMajor: Int,
        protocolMinor: Int,
        minimumProtocolMinor: Int,
    ): ProtocolReleaseSnapshot {
        val records = history(rootDir)
        val recorded = records.singleOrNull { it.releaseVersion == releaseVersion }
            ?: error("Release $releaseVersion has no frozen protocol record. Review the root version and run :protocol:protocol:prepareProtocolRelease, then commit the record before release.")
        check(recorded == records.last()) { "Release $releaseVersion is older than the latest reserved release ${records.last().releaseVersion}" }
        check(recorded.releaseBuildNumber == releaseBuildNumber && recorded.protocolMajor == protocolMajor &&
            recorded.protocolMinor == protocolMinor && recorded.minimumProtocolMinor == minimumProtocolMinor) {
            "Root version configuration differs from frozen release $releaseVersion; never rewrite an existing release record"
        }
        check(recorded.wireBaseline.readBytes().contentEquals(File(rootDir, DEVELOPMENT_BASELINE).readBytes())) {
            "Development wire schema differs from frozen release $releaseVersion; prepare a new release version"
        }
        return recorded
    }

    /** Explicit source edit, never a dependency that silently runs during publication. */
    fun prepare(
        rootDir: File,
        releaseVersion: String,
        releaseBuildNumber: Int,
        protocolMajor: Int,
        protocolMinor: Int,
        minimumProtocolMinor: Int,
    ): ProtocolReleaseSnapshot {
        check(releaseVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Invalid release version: $releaseVersion" }
        val records = history(rootDir)
        if (records.any { it.releaseVersion == releaseVersion }) {
            return verify(rootDir, releaseVersion, releaseBuildNumber, protocolMajor, protocolMinor, minimumProtocolMinor)
        }
        val baseline = File(rootDir, DEVELOPMENT_BASELINE)
        val candidate = ProtocolReleaseSnapshot(releaseVersion, releaseBuildNumber, protocolMajor, protocolMinor, minimumProtocolMinor, baseline)
        validateSnapshot(candidate)
        validateTransition(records.last(), candidate)
        val directory = File(rootDir, "$RELEASES/$releaseVersion")
        check(!directory.exists()) { "Release record directory already exists: $directory" }
        check(directory.mkdirs()) { "Cannot create release record: $directory" }
        baseline.copyTo(File(directory, "wire-baseline.tsv"))
        File(directory, "release.properties").writeText("""
            # Frozen release contract. Never overwrite or delete a committed record.
            releaseVersion=$releaseVersion
            releaseBuildNumber=$releaseBuildNumber
            protocolMajor=$protocolMajor
            protocolMinor=$protocolMinor
            minimumProtocolMinor=$minimumProtocolMinor
            wireSchemaSha256=${sha256(baseline)}
        """.trimIndent() + "\n")
        return verify(rootDir, releaseVersion, releaseBuildNumber, protocolMajor, protocolMinor, minimumProtocolMinor)
    }

    private fun history(rootDir: File): List<ProtocolReleaseSnapshot> {
        val directory = File(rootDir, RELEASES)
        check(directory.isDirectory) { "Missing local protocol release history: $directory" }
        val records = directory.listFiles().orEmpty().filter(File::isDirectory).map { entry ->
            val metadata = File(entry, "release.properties")
            val properties = Properties().apply { metadata.inputStream().use(::load) }
            fun required(key: String) = properties.getProperty(key) ?: error("Missing $key in $metadata")
            val baseline = File(entry, "wire-baseline.tsv")
            check(sha256(baseline) == required("wireSchemaSha256")) { "Frozen wire schema hash mismatch: $baseline" }
            ProtocolReleaseSnapshot(
                required("releaseVersion"), required("releaseBuildNumber").toInt(),
                required("protocolMajor").toInt(), required("protocolMinor").toInt(),
                required("minimumProtocolMinor").toInt(), baseline,
            ).also {
                check(it.releaseVersion == entry.name) { "Release record directory does not match version: $entry" }
                validateSnapshot(it)
            }
        }.sortedBy { it.releaseBuildNumber }
        check(records.isNotEmpty()) { "Protocol release history must retain the zero preview baseline" }
        check(records.first().releaseVersion == "0.0.0" && records.first().releaseBuildNumber == 0 &&
            records.first().protocolMajor == 0 && records.first().protocolMinor == 0) {
            "Protocol release history must retain the zero preview baseline"
        }
        records.zipWithNext().forEach { (previous, current) -> validateTransition(previous, current) }
        return records
    }

    private fun validateSnapshot(snapshot: ProtocolReleaseSnapshot) {
        check(snapshot.releaseVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Invalid frozen release version" }
        check(snapshot.releaseBuildNumber >= 0 && snapshot.protocolMajor in 0..32767 &&
            snapshot.protocolMinor in 0..65535 && snapshot.minimumProtocolMinor in 0..snapshot.protocolMinor) {
            "Invalid version range in release ${snapshot.releaseVersion}"
        }
        val schema = Schema.read(snapshot.wireBaseline)
        check(schema.major == snapshot.protocolMajor && schema.minor == snapshot.protocolMinor) {
            "Wire schema header differs from release ${snapshot.releaseVersion}"
        }
        schema.entries.values.forEach { entry ->
            check(entry.since in 0..schema.minor && (entry.removed == null || entry.removed in (entry.since + 1)..65535)) {
                "Invalid lifecycle for ${entry.identity} in release ${snapshot.releaseVersion}"
            }
            check(!entry.retired || (entry.removed != null && entry.removed <= snapshot.minimumProtocolMinor)) {
                "Retired wire ${entry.identity} is still required by the release compatibility window"
            }
            check(entry.retired || entry.removed == null || entry.removed > snapshot.minimumProtocolMinor) {
                "Expired wire ${entry.identity} must retire when the release compatibility floor reaches its removal version"
            }
        }
    }

    private fun validateTransition(previous: ProtocolReleaseSnapshot, current: ProtocolReleaseSnapshot) {
        check(current.releaseBuildNumber > previous.releaseBuildNumber) { "Release build number must advance beyond ${previous.releaseBuildNumber}" }
        val oldVersion = previous.releaseVersion.split('.').map(String::toInt)
        val newVersion = current.releaseVersion.split('.').map(String::toInt)
        check(oldVersion.zip(newVersion).firstOrNull { (old, new) -> old != new }?.let { (old, new) -> new > old } == true) {
            "Display release version must advance beyond ${previous.releaseVersion}"
        }
        if (current.protocolMajor != previous.protocolMajor) {
            check(current.protocolMajor == previous.protocolMajor + 1 && current.protocolMinor == 0 && current.minimumProtocolMinor == 0) {
                "A protocol major transition must advance exactly one major and start at minor 0"
            }
            return
        }
        check(current.minimumProtocolMinor >= previous.minimumProtocolMinor) { "Released minimum protocol minor must not move backwards" }
        val old = Schema.read(previous.wireBaseline).entries
        val fresh = Schema.read(current.wireBaseline).entries
        old.forEach { (identity, entry) ->
            val next = fresh[identity] ?: error("Published wire $identity cannot disappear; keep its ID tombstone until a new major")
            check(entry.signature == next.signature && entry.since == next.since) { "Published wire $identity cannot change within the same major" }
            check(!entry.retired || next.retired) { "Published wire tombstone $identity cannot be reused within the same major" }
            check(!next.retired || entry.retired || (entry.removed != null && entry.removed <= current.minimumProtocolMinor)) {
                "Published wire $identity must declare removal in an earlier release before its implementation retires"
            }
            check(entry.removed == next.removed || (entry.removed == null && next.removed != null && next.removed > previous.protocolMinor)) {
                "Published removal version cannot be rewritten: $identity"
            }
        }
        val additions = fresh.filterKeys { it !in old }.values
        additions.forEach { check(it.since > previous.protocolMinor) { "New wire ${it.identity} needs @SinceProtocol above released minor ${previous.protocolMinor}" } }
        val contractChanged = additions.isNotEmpty() || old.any { (identity, entry) -> entry.removed != fresh.getValue(identity).removed }
        val expectedMinor = previous.protocolMinor + if (contractChanged) 1 else 0
        check(current.protocolMinor == expectedMinor) {
            "Release protocol must be ${current.protocolMajor}.$expectedMinor after consolidating unpublished development increments (last release ${previous.protocolMajor}.${previous.protocolMinor}). Review root protocolMinor, unpublished @SinceProtocol/@RemovedInProtocol and compatibility branches; never renumber released contracts."
        }
        additions.forEach { check(it.since == current.protocolMinor) { "New release wire ${it.identity} must start in consolidated minor ${current.protocolMinor}" } }
    }

    private data class Entry(val identity: String, val since: Int, val removed: Int?, val retired: Boolean, val signature: String)
    private data class Schema(val major: Int, val minor: Int, val entries: Map<String, Entry>) {
        companion object {
            fun read(file: File): Schema {
                val lines = file.readLines().filter { it.isNotBlank() && !it.startsWith('#') }
                check(lines.size >= 2 && lines[0].startsWith("major=") && lines[1].startsWith("minor=")) { "Invalid wire schema header: $file" }
                val entries = lines.drop(2).map { line ->
                    val columns = line.split('\t')
                    check(columns.size == 6 && columns[4] in setOf("active", "retired")) { "Invalid wire schema entry in $file" }
                    Entry("${columns[0]}:${columns[1]}", columns[2].toInt(), columns[3].takeUnless { it == "-" }?.toInt(), columns[4] == "retired", columns[5])
                }
                check(entries.map { it.identity }.distinct().size == entries.size) { "Duplicate wire identity in $file" }
                return Schema(lines[0].substringAfter('=').toInt(), lines[1].substringAfter('=').toInt(), entries.associateBy { it.identity })
            }
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
