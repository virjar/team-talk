package com.virjar.tk.server.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal const val ANDROID_DOWNLOAD_ALIAS = "TeamTalk-android.apk"
private const val RECEIPT_NAME = ".teamtalk-client-release.json"
private const val MAX_METADATA_BYTES = 1024 * 1024

/** The descriptor pins the verified bytes even when publication atomically replaces the APK pathname. */
internal class AndroidDownloadSnapshot(
    val channel: FileChannel,
    val size: Long,
    val sha256: String,
    val filename: String,
    val displayName: String?,
    val version: String?,
) : Closeable {
    val managed: Boolean get() = version != null
    val url: String get() = "/downloads/${if (managed) filename else ANDROID_DOWNLOAD_ALIAS}"
    override fun close() = channel.close()
}

/** Receipts already published by the release task remain the authority; no new server-side receipt is written. */
internal fun openAndroidDownload(downloads: File): AndroidDownloadSnapshot? {
    val receiptFile = File(downloads, RECEIPT_NAME)
    val receiptBytes = if (Files.exists(receiptFile.toPath(), NOFOLLOW_LINKS)) readDownloadMetadata(receiptFile) else null
    val apk = resolveDirectDownload(downloads, ANDROID_DOWNLOAD_ALIAS) ?: run {
        check(receiptBytes == null) { "Published Android APK is missing" }
        return null
    }
    val channel = FileChannel.open(apk.toPath(), StandardOpenOption.READ)
    try {
        val size = channel.size()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(64 * 1024)
        var offset = 0L
        while (offset < size) {
            buffer.clear()
            val count = channel.read(buffer, offset)
            check(count > 0) { "Android APK changed while reading" }
            digest.update(buffer.array(), 0, count)
            offset += count
        }
        check(channel.size() == size) { "Android APK changed while reading" }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (receiptBytes == null) {
            check(!Files.exists(receiptFile.toPath(), NOFOLLOW_LINKS)) { "Android publication changed while reading" }
            return AndroidDownloadSnapshot(channel, size, hash, "Android-${hash.take(12)}.apk", null, null)
        }

        val receipt = Json.parseToJsonElement(receiptBytes.toString(Charsets.UTF_8)).jsonObject
        val version = receipt.string("version")
        check(version.matches(Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"))) { "Invalid publication version" }
        val files = receipt.getValue("files").jsonObject
        check(files.string(ANDROID_DOWNLOAD_ALIAS) == hash) { "Published Android APK checksum mismatch" }
        val history = when (receipt["distributionKind"]?.jsonPrimitive?.content ?: "release") {
            "release", "private-first" -> "v$version"
            "snapshot" -> {
                val revision = receipt.getValue("desktopRevision").jsonPrimitive.int
                check(revision in 1..65535) { "Invalid publication revision" }
                "snapshot/v$version/revision-$revision"
            }
            else -> error("Unknown publication kind")
        }
        val manifestFile = File(downloads, ".teamtalk-client-releases/$history/metadata/release-manifest.json")
        val manifestBytes = readDownloadMetadata(manifestFile)
        val manifestHash = MessageDigest.getInstance("SHA-256").digest(manifestBytes)
            .joinToString("") { "%02x".format(it) }
        check(files.string("metadata/release-manifest.json") == manifestHash &&
            receipt.string("manifestSha256") == manifestHash) { "Published Android manifest checksum mismatch" }
        val manifest = Json.parseToJsonElement(manifestBytes.toString(Charsets.UTF_8)).jsonObject
        check(manifest.string("version") == version &&
            manifest.getValue("buildNumber") == receipt.getValue("releaseBuildNumber")) { "Published Android version mismatch" }
        val client = manifest.getValue("client").jsonObject
        val displayName = client.string("displayName")
        val desktopName = client.string("desktopName")
        check(displayName.isNotBlank() && displayName.length <= 80 && displayName.none(Char::isISOControl)) { "Invalid Android display name" }
        check(desktopName.matches(Regex("[A-Za-z][A-Za-z0-9]{0,47}"))) { "Invalid Android installation name" }
        val apkRecord = manifest.getValue("files").jsonArray.map { it.jsonObject }.single {
            it.string("path") == "assets/$desktopName-$version-android.apk"
        }
        check(apkRecord.string("sha256") == hash && apkRecord.getValue("size").jsonPrimitive.long == size) {
            "Published Android APK differs from the manifest's installation identity"
        }
        check(readDownloadMetadata(receiptFile).contentEquals(receiptBytes)) { "Android publication changed while reading" }
        return AndroidDownloadSnapshot(channel, size, hash, "$desktopName-$version-${hash.take(12)}-android.apk", displayName, version)
    } catch (failure: Throwable) {
        channel.close()
        throw failure
    }
}

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private fun readDownloadMetadata(file: File): ByteArray {
    check(file.isFile && file.length() in 1L..MAX_METADATA_BYTES.toLong()) { "Download publication metadata is missing or invalid" }
    return file.inputStream().use { stream ->
        stream.readNBytes(MAX_METADATA_BYTES + 1).also {
            check(it.size in 1..MAX_METADATA_BYTES) { "Download publication metadata is too large" }
        }
    }
}
