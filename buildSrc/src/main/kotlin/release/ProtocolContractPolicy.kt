package release

import java.io.File
import java.util.Properties

/** An explicitly frozen wire contract, independent of a product installation. */
data class ProtocolContractSnapshot(
    val protocolMajor: Int,
    val protocolMinor: Int,
    val minimumProtocolMinor: Int,
    val wireBaseline: File,
)

/** Formal releases and any explicitly frozen historical contracts share the same number space. */
object ProtocolContractPolicy {
    private const val CONTRACTS = "protocol/protocol/contracts"
    private const val DEVELOPMENT_BASELINE = "protocol/protocol/wire-baseline.tsv"
    private val order = compareBy<ProtocolContractSnapshot>(
        { it.protocolMajor }, { it.protocolMinor }, { it.minimumProtocolMinor },
    )

    fun latest(rootDir: File): ProtocolContractSnapshot {
        val releases = ProtocolReleasePolicy.readHistory(rootDir).map { it.contract() }
        val contracts = File(rootDir, CONTRACTS).listFiles().orEmpty().filter(File::isDirectory).map { entry ->
            val metadata = File(entry, "contract.properties")
            val properties = Properties().apply { metadata.inputStream().use(::load) }
            fun required(key: String) = properties.getProperty(key) ?: error("Missing $key in $metadata")
            val baseline = File(entry, "wire-baseline.tsv")
            check(sha256(baseline) == required("wireSchemaSha256")) { "Frozen wire schema hash mismatch: $baseline" }
            ProtocolContractSnapshot(
                required("protocolMajor").toInt(), required("protocolMinor").toInt(),
                required("minimumProtocolMinor").toInt(), baseline,
            ).also {
                check(entry.name == "${it.protocolMajor}.${it.protocolMinor}") { "Protocol contract directory does not match metadata: $entry" }
                ProtocolContractRules.validate(it)
            }
        }
        val records = (releases + contracts).sortedWith(order)
        records.zipWithNext().forEach { (previous, current) ->
            ProtocolContractRules.validateTransition(previous, current)
            requireSameReservation(previous, current)
        }
        return records.last()
    }

    fun verify(rootDir: File, major: Int, minor: Int, minimum: Int): ProtocolContractSnapshot {
        val recorded = latest(rootDir)
        check(recorded.protocolMajor == major && recorded.protocolMinor == minor && recorded.minimumProtocolMinor == minimum) {
            "Protocol $major.$minor with minimum $minimum has no matching current frozen contract; explicitly prepare and commit the reviewed contract before distributing"
        }
        check(recorded.wireBaseline.readBytes().contentEquals(File(rootDir, DEVELOPMENT_BASELINE).readBytes())) {
            "Development wire schema differs from frozen protocol $major.$minor; never rewrite or reclaim a distributed contract"
        }
        return recorded
    }

    /**
     * 内测包使用本轮待发布契约，不创建兼容历史。只相对正式冻结基线检查，
     * 同一 pending minor 可继续演进；每次包仍记录实际源码与 TSV 哈希。
     */
    fun verifyDevelopment(rootDir: File, major: Int, minor: Int, minimum: Int): ProtocolContractSnapshot {
        val candidate = ProtocolContractSnapshot(major, minor, minimum, File(rootDir, DEVELOPMENT_BASELINE))
        validateCandidate(rootDir, candidate)
        return candidate
    }

    /** Explicit source edit only. Ordinary build and publication tasks never freeze a contract implicitly. */
    fun prepare(rootDir: File, major: Int, minor: Int, minimum: Int): ProtocolContractSnapshot {
        val directory = File(rootDir, "$CONTRACTS/$major.$minor")
        if (directory.exists()) return verify(rootDir, major, minor, minimum)
        val baseline = File(rootDir, DEVELOPMENT_BASELINE)
        val candidate = ProtocolContractSnapshot(major, minor, minimum, baseline)
        validateCandidate(rootDir, candidate)
        check(directory.mkdirs()) { "Cannot create protocol contract: $directory" }
        baseline.copyTo(File(directory, "wire-baseline.tsv"))
        File(directory, "contract.properties").writeText("""
            # Frozen distributed protocol contract. Never overwrite or delete a committed record.
            protocolMajor=$major
            protocolMinor=$minor
            minimumProtocolMinor=$minimum
            wireSchemaSha256=${sha256(baseline)}
        """.trimIndent() + "\n")
        return verify(rootDir, major, minor, minimum)
    }

    internal fun validateCandidate(rootDir: File, candidate: ProtocolContractSnapshot) {
        ProtocolContractRules.validate(candidate)
        val previous = latest(rootDir)
        check(order.compare(candidate, previous) >= 0) { "Distributed protocol versions and compatibility floors cannot move backwards" }
        ProtocolContractRules.validateTransition(previous, candidate)
        requireSameReservation(previous, candidate)
    }

    private fun requireSameReservation(previous: ProtocolContractSnapshot, current: ProtocolContractSnapshot) {
        if (order.compare(previous, current) == 0) {
            check(previous.wireBaseline.readBytes().contentEquals(current.wireBaseline.readBytes())) {
                "The same frozen protocol and compatibility window cannot describe different wire schemas"
            }
        }
    }
}

/** One set of wire evolution rules applies to every distribution channel. */
internal object ProtocolContractRules {
    fun validate(snapshot: ProtocolContractSnapshot) {
        check(snapshot.protocolMajor in 0..32767 && snapshot.protocolMinor in 0..65535 &&
            snapshot.minimumProtocolMinor in 0..snapshot.protocolMinor) { "Invalid protocol version range" }
        val schema = Schema.read(snapshot.wireBaseline)
        check(schema.major == snapshot.protocolMajor && schema.minor == snapshot.protocolMinor) { "Wire schema header differs from frozen protocol metadata" }
        schema.entries.values.forEach { entry ->
            check(entry.since in 0..schema.minor && (entry.removed == null || entry.removed in (entry.since + 1)..65535)) {
                "Invalid lifecycle for ${entry.identity}"
            }
            check(!entry.retired || (entry.removed != null && entry.removed <= snapshot.minimumProtocolMinor)) {
                "Retired wire ${entry.identity} is still required by the compatibility window"
            }
            check(entry.retired || entry.removed == null || entry.removed > snapshot.minimumProtocolMinor) {
                "Expired wire ${entry.identity} must retire when the compatibility floor reaches its removal version"
            }
        }
    }

    fun validateTransition(previous: ProtocolContractSnapshot, current: ProtocolContractSnapshot) {
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
                "Published wire $identity must declare removal in an earlier distribution before its implementation retires"
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
            "Release protocol must be ${current.protocolMajor}.$expectedMinor after consolidating unpublished development increments (last distribution ${previous.protocolMajor}.${previous.protocolMinor}). Review root protocolMinor, unpublished @SinceProtocol/@RemovedInProtocol and compatibility branches; never renumber released contracts."
        }
        additions.forEach { check(it.since == current.protocolMinor) { "New distributed wire ${it.identity} must start in consolidated minor ${current.protocolMinor}" } }
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
}
