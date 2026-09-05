package com.virjar.tk.server.api

import com.virjar.tk.protocol.body.AttachmentPolicy
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readByte
import kotlinx.coroutines.CancellationException
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val MAX_MULTIPART_OVERHEAD_BYTES: Long = 64L * 1024L
internal const val DEFAULT_UPLOAD_STAGING_TIMEOUT_MILLIS: Long = 30L * 60L * 1_000L
internal const val UPLOAD_BODY_REJECTION_MESSAGE = "Rejected attachment upload request body"
internal const val UPLOAD_STAGING_TIMEOUT_MESSAGE = "Attachment upload staging timed out"

private const val MAX_MULTIPART_BOUNDARY_BYTES = 70
private const val MAX_MULTIPART_HEADER_LINE_BYTES = 4 * 1024
private const val MAX_MULTIPART_HEADER_BYTES = 16 * 1024
private const val MAX_MULTIPART_HEADER_COUNT = 3
private const val COPY_BUFFER_BYTES = 64 * 1024

internal data class StrictMultipartEnvelope(
    val contentLength: Long,
    val boundary: String,
)

internal data class StrictMultipartFile(
    val originalName: String,
    val contentType: String,
    val payloadLength: Long,
)

internal data class StrictMultipartStagingTarget<T>(
    val file: File,
    val owner: T,
)

internal data class StrictStagedFile<T>(
    val originalName: String,
    val contentType: String,
    val payloadLength: Long,
    val payloadSha256: String,
    val file: File,
    val owner: T,
)

internal class UploadBodyRejection(
    val status: HttpStatusCode,
    val responseMessage: String,
) : IllegalArgumentException(UPLOAD_BODY_REJECTION_MESSAGE)

internal fun strictMultipartEnvelope(
    headers: Headers,
    maxUploadBytes: Long,
): StrictMultipartEnvelope {
    if (!headers.getAll(HttpHeaders.TransferEncoding).isNullOrEmpty()) {
        rejectBody(HttpStatusCode.LengthRequired, "A valid Content-Length header is required")
    }
    val lengthValues = headers.getAll(HttpHeaders.ContentLength)
    if (lengthValues.isNullOrEmpty()) {
        rejectBody(HttpStatusCode.LengthRequired, "A valid Content-Length header is required")
    }
    if (lengthValues.size != 1) rejectMalformed()
    val lengthText = lengthValues.single()
    if (lengthText.isEmpty() || lengthText.any { it !in '0'..'9' }) rejectMalformed()
    val contentLength = lengthText.toLongOrNull() ?: rejectMalformed()
    if (contentLength <= 0L) rejectMalformed()
    val requestLimit = Math.addExact(maxUploadBytes, MAX_MULTIPART_OVERHEAD_BYTES)
    if (contentLength > requestLimit) {
        rejectBody(HttpStatusCode.PayloadTooLarge, uploadLimitMessage(maxUploadBytes))
    }

    val contentTypeValues = headers.getAll(HttpHeaders.ContentType)
    if (contentTypeValues == null || contentTypeValues.size != 1) rejectMalformed()
    val contentType = try {
        ContentType.parse(contentTypeValues.single())
    } catch (_: Exception) {
        rejectMalformed()
    }
    if (!contentType.withoutParameters().match(ContentType.MultiPart.FormData)) rejectMalformed()
    if (contentType.parameters.any { !it.name.equals("boundary", ignoreCase = true) }) rejectMalformed()
    val boundaries = contentType.parameters.filter { it.name.equals("boundary", ignoreCase = true) }
    if (boundaries.size != 1) rejectMalformed()
    val boundary = boundaries.single().value
    if (!isValidBoundary(boundary)) rejectMalformed()
    return StrictMultipartEnvelope(contentLength, boundary)
}

/**
 * 在要求 [prepareTarget] 预留资源并创建目标位置之前，先解析唯一允许的分部（part）的框架。
 * 因此回调在精确的载荷长度已知之后、但在任何载荷字节被复制
 * 或目标文件被要求存在之前运行。
 */
internal suspend fun <T> ByteReadChannel.stageStrictSingleFileMultipart(
    envelope: StrictMultipartEnvelope,
    maxUploadBytes: Long,
    prepareTarget: (StrictMultipartFile) -> StrictMultipartStagingTarget<T>,
): StrictStagedFile<T> {
    val reader = CountingMultipartReader(this, envelope.contentLength)
    try {
        reader.expectAscii("--${envelope.boundary}\r\n")
        val headers = readPartHeaders(reader)
        val disposition = parseFileDisposition(
            headers.singleValue("content-disposition"),
        )
        val partContentTypeText = headers.singleValue("content-type")
        if (partContentTypeText.length > AttachmentPolicy.MAX_CONTENT_TYPE_LENGTH) rejectMalformed()
        val partContentType = try {
            ContentType.parse(partContentTypeText).toString()
        } catch (_: Exception) {
            rejectMalformed()
        }

        val terminalSuffix = "\r\n--${envelope.boundary}--\r\n".encodeToByteArray()
        val payloadLength = envelope.contentLength - reader.consumedBytes - terminalSuffix.size
        if (payloadLength < 0L) rejectMalformed()
        if (payloadLength > maxUploadBytes) {
            rejectBody(HttpStatusCode.PayloadTooLarge, uploadLimitMessage(maxUploadBytes))
        }
        val declaredPartLength = headers.singleValue("content-length")
        if (
            declaredPartLength.isEmpty() ||
            declaredPartLength.any { it !in '0'..'9' } ||
            declaredPartLength.toLongOrNull() != payloadLength
        ) {
            rejectMalformed()
        }

        val metadata = StrictMultipartFile(disposition, partContentType, payloadLength)
        val staging = prepareTarget(metadata)
        // 该协议按长度分帧：载荷字节是不透明的，包括形似分隔符的序列。
        // 无法声明第二个 MIME 分部，因为其字节会使必需的 Content-Length
        // 与推导出的载荷长度不一致。
        val payloadSha256 = reader.copyExactTo(staging.file, payloadLength)
        reader.expect(terminalSuffix)
        if (reader.consumedBytes != envelope.contentLength) rejectMalformed()
        return StrictStagedFile(
            originalName = metadata.originalName,
            contentType = metadata.contentType,
            payloadLength = metadata.payloadLength,
            payloadSha256 = payloadSha256,
            file = staging.file,
            owner = staging.owner,
        )
    } catch (rejection: UploadBodyRejection) {
        throw rejection
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: EOFException) {
        rejectMalformed()
    }
}

internal fun ByteReadChannel.cancelRejectedUpload(message: String = UPLOAD_BODY_REJECTION_MESSAGE) {
    cancel(CancellationException(message))
}

private suspend fun readPartHeaders(reader: CountingMultipartReader): StrictPartHeaders {
    val values = LinkedHashMap<String, String>()
    var headerBytes = 0
    var headerCount = 0
    while (true) {
        val line = reader.readCrlfLine(MAX_MULTIPART_HEADER_LINE_BYTES)
        headerBytes += line.byteCountWithCrlf
        if (headerBytes > MAX_MULTIPART_HEADER_BYTES) rejectMalformed()
        if (line.bytes.isEmpty()) break
        headerCount += 1
        if (headerCount > MAX_MULTIPART_HEADER_COUNT) rejectMalformed()
        val text = decodeUtf8Strict(line.bytes)
        val colon = text.indexOf(':')
        if (colon <= 0) rejectMalformed()
        val name = text.substring(0, colon)
        if (!name.all(::isHeaderNameCharacter)) rejectMalformed()
        val normalizedName = name.lowercase()
        if (normalizedName !in ALLOWED_PART_HEADERS || values.containsKey(normalizedName)) {
            rejectMalformed()
        }
        val value = text.substring(colon + 1).trim()
        if (value.isEmpty() || value.any { it.code < 0x20 || it.code == 0x7f }) {
            rejectMalformed()
        }
        values[normalizedName] = value
    }
    if (values.keys.none { it == "content-disposition" } || values.keys.none { it == "content-type" }) {
        rejectMalformed()
    }
    return StrictPartHeaders(values)
}

private fun parseFileDisposition(value: String): String {
    val parser = DispositionParser(value)
    if (!parser.readToken().equals("form-data", ignoreCase = true)) rejectMalformed()
    val parameters = LinkedHashMap<String, String>()
    while (parser.hasRemaining()) {
        parser.expectSemicolon()
        val name = parser.readToken().lowercase()
        if (name !in setOf("name", "filename") || parameters.containsKey(name)) rejectMalformed()
        parser.expectEquals()
        parameters[name] = parser.readParameterValue()
    }
    if (parameters["name"] != "file") rejectMalformed()
    val filename = parameters["filename"] ?: rejectMalformed()
    if (
        filename.isEmpty() ||
        filename.length > AttachmentPolicy.MAX_NAME_LENGTH ||
        filename.any { it.code < 0x20 || it.code == 0x7f }
    ) {
        rejectMalformed()
    }
    return filename
}

private class DispositionParser(private val source: String) {
    private var index = 0

    fun hasRemaining(): Boolean {
        skipSpaces()
        return index < source.length
    }

    fun expectSemicolon() {
        skipSpaces()
        if (index >= source.length || source[index] != ';') rejectMalformed()
        index += 1
    }

    fun expectEquals() {
        skipSpaces()
        if (index >= source.length || source[index] != '=') rejectMalformed()
        index += 1
    }

    fun readToken(): String {
        skipSpaces()
        val start = index
        while (index < source.length && isDispositionTokenCharacter(source[index])) index += 1
        if (start == index) rejectMalformed()
        val token = source.substring(start, index)
        skipSpaces()
        return token
    }

    fun readParameterValue(): String {
        skipSpaces()
        if (index >= source.length) rejectMalformed()
        if (source[index] != '"') return readToken()
        index += 1
        val result = StringBuilder()
        var closed = false
        while (index < source.length) {
            val current = source[index++]
            when (current) {
                '"' -> {
                    closed = true
                    break
                }
                '\\' -> {
                    if (index >= source.length) rejectMalformed()
                    result.append(source[index++])
                }
                in '\u0000'..'\u001f', '\u007f' -> rejectMalformed()
                else -> result.append(current)
            }
        }
        if (!closed) rejectMalformed()
        skipSpaces()
        if (index < source.length && source[index] != ';') rejectMalformed()
        return result.toString()
    }

    private fun skipSpaces() {
        while (index < source.length && (source[index] == ' ' || source[index] == '\t')) index += 1
    }
}

private class CountingMultipartReader(
    private val channel: ByteReadChannel,
    private val declaredLength: Long,
) {
    var consumedBytes: Long = 0L
        private set

    suspend fun expectAscii(expected: String) = expect(expected.encodeToByteArray())

    suspend fun expect(expected: ByteArray) {
        expected.forEach { byte ->
            if (readByte() != byte) rejectMalformed()
        }
    }

    suspend fun readCrlfLine(maxLineBytes: Int): MultipartLine {
        val bytes = ArrayList<Byte>()
        while (true) {
            val byte = readByte()
            if (byte == '\r'.code.toByte()) {
                if (readByte() != '\n'.code.toByte()) rejectMalformed()
                return MultipartLine(bytes.toByteArray(), bytes.size + 2)
            }
            if (byte == '\n'.code.toByte() || bytes.size >= maxLineBytes) rejectMalformed()
            bytes += byte
        }
    }

    suspend fun copyExactTo(target: File, byteCount: Long): String {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        var remaining = byteCount
        target.outputStream().buffered().use { output ->
            while (remaining > 0L) {
                val count = minOf(buffer.size.toLong(), remaining).toInt()
                val read = channel.readAvailable(buffer, 0, count)
                if (read < 0) throw EOFException()
                if (read == 0) continue
                if (consumedBytes > declaredLength - read) rejectMalformed()
                consumedBytes += read
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        return digest.digest().toLowerHex()
    }

    private suspend fun readByte(): Byte {
        if (consumedBytes >= declaredLength) throw EOFException()
        val value = channel.readByte()
        consumedBytes += 1
        return value
    }
}

private data class MultipartLine(val bytes: ByteArray, val byteCountWithCrlf: Int)

private class StrictPartHeaders(private val values: Map<String, String>) {
    fun singleValue(name: String): String = values[name] ?: rejectMalformed()
}

private fun decodeUtf8Strict(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: Exception) {
    rejectMalformed()
}

private fun isValidBoundary(boundary: String): Boolean =
    boundary.isNotEmpty() &&
        boundary.encodeToByteArray().size <= MAX_MULTIPART_BOUNDARY_BYTES &&
        boundary.all { it.code in 0x21..0x7e && it !in BOUNDARY_FORBIDDEN_CHARACTERS }

private fun isHeaderNameCharacter(character: Char): Boolean =
    character.isLetterOrDigit() || character in "!#$%&'*+-.^_`|~"

private fun isDispositionTokenCharacter(character: Char): Boolean =
    character.code in 0x21..0x7e && character !in "()<>@,;:\\\"/[]?={} \t"

private fun uploadLimitMessage(maxUploadBytes: Long): String =
    "Attachment exceeds the ${maxUploadBytes / (1024 * 1024)} MB upload limit"

private fun rejectMalformed(): Nothing =
    rejectBody(HttpStatusCode.BadRequest, "Exactly one well-formed file part is required")

private fun rejectBody(status: HttpStatusCode, message: String): Nothing =
    throw UploadBodyRejection(status, message)

private fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private val ALLOWED_PART_HEADERS = setOf("content-disposition", "content-type", "content-length")
private val BOUNDARY_FORBIDDEN_CHARACTERS = setOf('"', '(', ')', ',', '/', ':', ';', '<', '=', '>', '?', '@', '[', '\\', ']')
