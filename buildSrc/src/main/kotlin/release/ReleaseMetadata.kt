package release

import deployment.ProcessOutputMode
import deployment.localChecked
import java.io.File
import java.util.Properties

/** A tag names this committed configuration; command-line properties cannot invent a release. */
data class ReleaseVersion(
    val name: String,
    val buildNumber: Int,
    val protocolMajor: Int,
    val protocolMinor: Int,
    val minimumProtocolMinor: Int,
) {
    val tag: String get() = "v$name"

    companion object {
        fun read(root: File): ReleaseVersion = parse(File(root, "gradle.properties").readText())

        fun parse(content: String): ReleaseVersion {
            val props = Properties().apply { content.reader().use(::load) }
            fun number(key: String, maximum: Int): Int = props.getProperty("teamtalk.$key")
                ?.toIntOrNull()?.takeIf { it in 0..maximum }
                ?: error("teamtalk.$key must be in 0..$maximum in root gradle.properties")
            val name = props.getProperty("teamtalk.releaseVersion").orEmpty()
            require(name.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) {
                "Root teamtalk.releaseVersion must be a numeric three-part version"
            }
            val minor = number("protocolMinor", 65535)
            return ReleaseVersion(name, number("releaseBuildNumber", 2_099_999_999),
                number("protocolMajor", 32767), minor, number("minimumProtocolMinor", minor))
        }
    }
}

class ReleaseMetadata(private val root: File) {
    fun notesFile(version: ReleaseVersion): File = File(root, "doc/07-operations/releases/${version.name}.md")

    fun verify(version: ReleaseVersion, expectedRevision: String, requireClean: Boolean = true): String {
        require(ReleaseVersion.read(root) == version) {
            "Release values must come from committed root gradle.properties; -P overrides are not release inputs"
        }
        require(git("rev-parse", "HEAD") == expectedRevision) { "Source revision changed during release" }
        if (requireClean) require(git("status", "--porcelain", "--untracked-files=normal").isBlank()) {
            "Commit release configuration, human-written notes and protocol snapshots before release"
        }
        val notes = notesFile(version)
        require(notes.isFile) { "Write and commit release notes: ${notes.relativeTo(root)}" }
        val text = notes.readText()
        require(text.lineSequence().firstOrNull()?.trim() == "# TeamTalk ${version.name}" &&
            text.substringAfter('\n', "").isNotBlank()) {
            "Release notes must start with '# TeamTalk ${version.name}' and describe changes and upgrade impact"
        }
        return text
    }

    /** Used by CI before building: JVM flags or provisional protocol bumps alone are not releases. */
    fun releaseChangedSince(baseRevision: String): Boolean {
        require(baseRevision.matches(Regex("[0-9a-fA-F]{40}"))) { "releaseBase must be a full Git commit" }
        val previous = ReleaseVersion.parse(git("show", "$baseRevision:gradle.properties"))
        val current = ReleaseVersion.read(root)
        val changed = previous.name != current.name || previous.buildNumber != current.buildNumber
        if (changed) {
            require(previous.name != current.name && current.buildNumber > previous.buildNumber) {
                "A release changes the readable version and increases releaseBuildNumber together"
            }
            require(compareVersions(current.name, previous.name) > 0) { "Release version must increase" }
        }
        return changed
    }

    fun verifyFrozenHistorySince(baseRevision: String) {
        require(baseRevision.matches(Regex("[0-9a-fA-F]{40}"))) { "releaseBase must be a full Git commit" }
        val rewritten = git("diff", "--name-only", "--diff-filter=DMRT", baseRevision, "HEAD", "--", "protocol/protocol/releases")
        require(rewritten.isBlank()) { "Previously committed protocol release records must not be rewritten or removed:\n$rewritten" }
    }

    /** Commit detail is a separate appendix; it never overwrites the maintainer's release text. */
    fun commitAppendix(version: ReleaseVersion): String {
        val ancestors = git("tag", "--merged", "HEAD").lineSequence()
            .filter { it.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")) && it != version.tag }
            .filter { compareVersions(it.drop(1), version.name) < 0 }
            .sortedWith { a, b -> compareVersions(a.drop(1), b.drop(1)) }.toList()
        val base = ancestors.lastOrNull()
        val range = base?.let { "$it..HEAD" } ?: "HEAD"
        val scratch = File(root, "build/release-metadata").apply { mkdirs() }
        val log = File.createTempFile("commits-", ".txt", scratch)
        val commits = try {
            // A long private history may have no tags. Do not truncate it through bounded console capture.
            git("log", "--no-show-signature", "--format=- %s (%h)", "--output=${log.absolutePath}", range)
            log.readText().trimEnd()
        } finally {
            log.delete()
        }
        return "# Commit appendix\n\n" +
            (base?.let { "Range: $it → ${version.tag}.\n\n" }
                ?: "No previous release tag is available; this is the reachable source history.\n\n") +
            commits + "\n"
    }

    fun git(vararg args: String): String {
        val result = localChecked(
        "read release source metadata", listOf("git", *args), workingDirectory = root,
        outputMode = ProcessOutputMode.CAPTURE,
        )
        check(!result.outputTruncated) { "Git output exceeded the release metadata limit; narrow the commit range" }
        return result.output.trim()
    }
}

private fun compareVersions(left: String, right: String): Int {
    left.split('.').map(String::toBigInteger).zip(right.split('.').map(String::toBigInteger)).forEach { (a, b) ->
        val result = a.compareTo(b)
        if (result != 0) return result
    }
    return 0
}
