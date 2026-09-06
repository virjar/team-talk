package release

import java.io.File
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

    fun latest(rootDir: File): ProtocolReleaseSnapshot {
        ProtocolContractPolicy.latest(rootDir)
        return readHistory(rootDir).last()
    }

    fun verify(
        rootDir: File,
        releaseVersion: String,
        releaseBuildNumber: Int,
        protocolMajor: Int,
        protocolMinor: Int,
        minimumProtocolMinor: Int,
    ): ProtocolReleaseSnapshot {
        val records = readHistory(rootDir)
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
        ProtocolContractPolicy.verify(rootDir, protocolMajor, protocolMinor, minimumProtocolMinor)
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
        val records = readHistory(rootDir)
        if (records.any { it.releaseVersion == releaseVersion }) {
            return verify(rootDir, releaseVersion, releaseBuildNumber, protocolMajor, protocolMinor, minimumProtocolMinor)
        }
        val baseline = File(rootDir, DEVELOPMENT_BASELINE)
        val candidate = ProtocolReleaseSnapshot(releaseVersion, releaseBuildNumber, protocolMajor, protocolMinor, minimumProtocolMinor, baseline)
        ProtocolContractRules.validate(candidate.contract())
        validateReleaseTransition(records.last(), candidate)
        ProtocolContractPolicy.validateCandidate(rootDir, candidate.contract())
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

    internal fun readHistory(rootDir: File): List<ProtocolReleaseSnapshot> {
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
                check(it.releaseVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) && it.releaseBuildNumber >= 0) { "Invalid frozen release version" }
                ProtocolContractRules.validate(it.contract())
            }
        }.sortedBy { it.releaseBuildNumber }
        check(records.isNotEmpty()) { "Protocol release history must retain the zero preview baseline" }
        check(records.first().releaseVersion == "0.0.0" && records.first().releaseBuildNumber == 0 &&
            records.first().protocolMajor == 0 && records.first().protocolMinor == 0) {
            "Protocol release history must retain the zero preview baseline"
        }
        records.zipWithNext().forEach { (previous, current) -> validateReleaseTransition(previous, current) }
        return records
    }

    private fun validateReleaseTransition(previous: ProtocolReleaseSnapshot, current: ProtocolReleaseSnapshot) {
        check(current.releaseBuildNumber > previous.releaseBuildNumber) { "Release build number must advance beyond ${previous.releaseBuildNumber}" }
        val oldVersion = previous.releaseVersion.split('.').map(String::toInt)
        val newVersion = current.releaseVersion.split('.').map(String::toInt)
        check(oldVersion.zip(newVersion).firstOrNull { (old, new) -> old != new }?.let { (old, new) -> new > old } == true) {
            "Display release version must advance beyond ${previous.releaseVersion}"
        }
        check(current.protocolMajor > previous.protocolMajor ||
            (current.protocolMajor == previous.protocolMajor && current.protocolMinor >= previous.protocolMinor &&
                current.minimumProtocolMinor >= previous.minimumProtocolMinor)) {
            "Product releases cannot move behind previously distributed protocol versions"
        }
    }
}

internal fun ProtocolReleaseSnapshot.contract() = ProtocolContractSnapshot(
    protocolMajor, protocolMinor, minimumProtocolMinor, wireBaseline,
)
