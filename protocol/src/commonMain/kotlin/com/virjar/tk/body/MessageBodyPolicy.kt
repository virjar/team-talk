package com.virjar.tk.body

import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType

/**
 * 消息类型与消息体的一致性契约。
 *
 * SDK 发送前和服务端落库前共用：禁止 `messageType` 与 body 错配；Markdown 消息的
 * mentions/plainText 是派生数据，必须从 markdown 权威源重建，不能信任调用方上传值。
 */
object MessageBodyPolicy {

    /**
     * 即时消息不是文档存储。限制正文规模和结构标记数量，避免一个合法 wire frame
     * 在 Markdown 解析、搜索索引或多端渲染时放大为不可控的 CPU/内存消耗。
     */
    const val MAX_MARKDOWN_LENGTH = 100_000
    const val MAX_MARKDOWN_LINES = 4_000
    const val MAX_MARKDOWN_STRUCTURE_MARKERS = 32_000
    const val MAX_BLOCK_QUOTE_NESTING = 64
    const val MAX_MARKDOWN_TABLE_COLUMNS = 32
    const val MAX_MARKDOWN_TABLE_CELLS = 1_000
    const val MAX_INTERACTIVE_CARD_JSON_LENGTH = 100_000
    const val MAX_INTERACTIVE_CARD_BLOCKS = 100
    const val MAX_INTERACTIVE_CARD_TEXT_LENGTH = 20_000
    const val MAX_CHAT_ID_LENGTH = 256
    const val MAX_CLIENT_MESSAGE_ID_LENGTH = 256
    const val MAX_IDENTIFIER_LENGTH = 256
    const val MAX_DISPLAY_NAME_LENGTH = 512
    const val MAX_SHORT_TEXT_LENGTH = 4_000
    const val MAX_URL_LENGTH = 4_096
    const val MAX_EMOJI_LENGTH = 64
    const val MAX_COORDINATE_TEXT_LENGTH = 64
    const val MAX_MEDIA_DIMENSION = 100_000
    const val MAX_MEDIA_DURATION_SECONDS = 7 * 24 * 60 * 60
    const val MAX_MERGE_FORWARD_MESSAGE_COUNT = 10_000

    /**
     * Wire 在构造 String 之前只能按 UTF-8 字节数限流。每个 Unicode 标量最多占
     * 4 字节；用字符预算的四倍可兼容完整合法正文，同时把单字段预分配限制在业务量级。
     */
    const val MAX_UTF8_BYTES_PER_CHARACTER = 4

    fun utf8WireLimit(maxCharacters: Int): Int {
        require(maxCharacters >= 0 && maxCharacters <= Int.MAX_VALUE / MAX_UTF8_BYTES_PER_CHARACTER) {
            "字符预算无法转换为安全的 UTF-8 wire 预算"
        }
        return maxCharacters * MAX_UTF8_BYTES_PER_CHARACTER
    }

    @Suppress("DEPRECATION")
    fun canonicalize(message: Message): Message {
        require(message.chatId.isNotBlank() && message.chatId.length <= MAX_CHAT_ID_LENGTH) { "chatId 非法" }
        require(
            message.clientMsgId.isNotBlank() &&
                message.clientMsgId.length <= MAX_CLIENT_MESSAGE_ID_LENGTH,
        ) { "clientMsgId 非法" }
        require(message.senderUid.length <= MAX_IDENTIFIER_LENGTH) { "senderUid 过长" }

        // TYPING 是复用 Message 信封的瞬态协议，不持久化也没有 MessageBody。
        // 显式建模这一个例外，避免它绕开类型校验，也避免普通正文策略误杀合法输入状态。
        if (message.messageType == MessageType.TYPING.code) {
            require(message.body == null) { "输入状态消息不能携带正文" }
            return message
        }

        val body = requireNotNull(message.body) { "消息体不能为空" }
        val expectedType = typeOf(body)
        require(message.messageType == expectedType.code) {
            "消息类型与消息体不匹配: body=${body::class.simpleName}, messageType=${message.messageType}"
        }
        val canonicalBody = when (body) {
            // TEXT 仍需兼容旧客户端发送，但不能成为绕过 Markdown 资源预算的旁路。
            is TextBody -> body.copy(text = validateMarkdown(body.text))
            is RichTextBody -> buildRichTextBody(validateMarkdown(body.markdown)).also {
                require(it.mentions.size <= RichTextBody.MAX_MENTIONS) {
                    "单条消息不能超过 ${RichTextBody.MAX_MENTIONS} 个 mention"
                }
            }
            is ReplyBody -> validateReply(body)
            is InteractiveCardBody -> validateInteractiveCard(body)
            is ImageBody -> validateImage(body)
            is VoiceBody -> validateVoice(body)
            is VideoBody -> validateVideo(body)
            is FileBody -> validateAttachmentBody(body)
            is LocationBody -> validateLocation(body)
            is CardBody -> validateContactCard(body)
            is ForwardBody -> validateForward(body)
            is MergeForwardBody -> validateMergeForward(body)
            is RevokeBody -> body.also { requireIdentifier(it.revokedMsgId, "revokedMsgId") }
            is EditBody -> body.copy(
                editedMsgId = requireIdentifier(body.editedMsgId, "editedMsgId"),
                newContent = validateMarkdown(body.newContent),
            )
            is StickerBody -> validateSticker(body)
            is ReactionBody -> validateReaction(body)
        }
        return if (canonicalBody == body) message else message.copy(body = canonicalBody)
    }

    /** 返回原文，便于 canonicalize 以表达式形式重建消息体。 */
    fun validateMarkdown(markdown: String): String {
        require(markdown.length <= MAX_MARKDOWN_LENGTH) {
            "Markdown 正文不能超过 $MAX_MARKDOWN_LENGTH 个字符"
        }

        var lineCount = 1
        var structureMarkers = 0
        markdown.forEach { char ->
            if (char == '\n') lineCount++
            if (char in MARKDOWN_STRUCTURE_CHARACTERS) structureMarkers++
        }
        require(lineCount <= MAX_MARKDOWN_LINES) {
            "Markdown 正文不能超过 $MAX_MARKDOWN_LINES 行"
        }
        require(structureMarkers <= MAX_MARKDOWN_STRUCTURE_MARKERS) {
            "Markdown 结构标记过多（最多 $MAX_MARKDOWN_STRUCTURE_MARKERS 个）"
        }
        require(maxBlockQuoteNesting(markdown) <= MAX_BLOCK_QUOTE_NESTING) {
            "Markdown 引用嵌套不能超过 $MAX_BLOCK_QUOTE_NESTING 层"
        }
        validateTableBudget(markdown)
        return markdown
    }

    private fun maxBlockQuoteNesting(markdown: String): Int {
        var maximum = 0
        var fence: Fence? = null
        markdown.lines().forEach { rawLine ->
            val marker = fenceMarker(stripTableContainerPrefix(rawLine))
            if (fence == null && marker != null) {
                fence = marker
                return@forEach
            }
            val active = fence
            if (active != null) {
                if (
                    marker != null && marker.character == active.character &&
                    marker.length >= active.length && marker.closing
                ) {
                    fence = null
                }
                return@forEach
            }

            var index = 0
            while (index < rawLine.length) {
                if (rawLine[index] != '>') {
                    index++
                    continue
                }

                var cursor = index
                var depth = 0
                while (cursor < rawLine.length && rawLine[cursor] == '>') {
                    depth++
                    cursor++
                    var spacing = 0
                    while (
                        cursor < rawLine.length &&
                        (rawLine[cursor] == ' ' || rawLine[cursor] == '\t') &&
                        spacing < 3
                    ) {
                        cursor++
                        spacing++
                    }
                }
                if (depth > maximum) maximum = depth
                index = if (cursor > index) cursor else index + 1
            }
        }
        return maximum
    }

    /**
     * 表格会把一个很短的 Markdown 行放大为大量 Compose 节点。这里用线性扫描识别
     * GFM 分隔行，并在进入客户端解析器前限制列数及一条消息内的总渲染单元格。
     * 围栏代码里的竖线只是源码，不参与表格预算。
     */
    private fun validateTableBudget(markdown: String) {
        val lines = markdown.lines()
        val tableLines = ArrayList<String?>(lines.size)
        var fence: Fence? = null

        lines.forEach { rawLine ->
            val line = stripTableContainerPrefix(rawLine)
            val marker = fenceMarker(line)
            if (fence == null) {
                if (marker != null) fence = marker
                tableLines += if (marker == null) line else null
            } else {
                tableLines += null
                val active = requireNotNull(fence)
                if (
                    marker != null && marker.character == active.character &&
                    marker.length >= active.length && marker.closing
                ) {
                    fence = null
                }
            }
        }

        var totalCells = 0
        var index = 1
        while (index < tableLines.size) {
            val delimiter = tableLines[index]
            val header = tableLines[index - 1]
            val delimiterColumns = delimiter?.let(::tableDelimiterColumnCount)
            val headerColumns = header?.let(::tableColumnCount)
            if (delimiterColumns == null || headerColumns == null || headerColumns != delimiterColumns) {
                index++
                continue
            }

            var columnCount: Int = requireNotNull(delimiterColumns)
            var renderedRows = 1 // 表头；分隔行不渲染为单元格。
            var rowIndex = index + 1
            while (rowIndex < tableLines.size) {
                val row = tableLines[rowIndex] ?: break
                if (row.isBlank()) break
                val rowColumns = tableColumnCount(row) ?: break
                columnCount = maxOf(columnCount, rowColumns)
                renderedRows++
                rowIndex++
            }

            require(columnCount <= MAX_MARKDOWN_TABLE_COLUMNS) {
                "Markdown 表格不能超过 $MAX_MARKDOWN_TABLE_COLUMNS 列"
            }
            totalCells += columnCount * renderedRows
            require(totalCells <= MAX_MARKDOWN_TABLE_CELLS) {
                "Markdown 表格单元格总数不能超过 $MAX_MARKDOWN_TABLE_CELLS 个"
            }
            index = maxOf(rowIndex, index + 1)
        }
    }

    private fun tableDelimiterColumnCount(line: String): Int? {
        if (unescapedPipeCount(line) == 0) return null
        val start = line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return null
        val end = line.indexOfLast { !it.isWhitespace() }
        var cellStart = if (line[start] == '|') start + 1 else start
        val contentEnd = if (line[end] == '|' && !isEscaped(line, end)) end else end + 1
        var columns = 0
        var cursor = cellStart
        while (cursor <= contentEnd) {
            val separator = nextUnescapedPipe(line, cursor, contentEnd)
            val cellEnd = if (separator >= 0) separator else contentEnd
            if (!isTableDelimiterCell(line, cellStart, cellEnd)) return null
            columns++
            if (separator < 0) break
            cellStart = separator + 1
            cursor = cellStart
        }
        return columns.takeIf { it > 0 }
    }

    private fun tableColumnCount(line: String): Int? {
        val pipeCount = unescapedPipeCount(line)
        if (pipeCount == 0) return null
        val trimmedStart = line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return null
        val trimmedEnd = line.indexOfLast { !it.isWhitespace() }
        val leading = line[trimmedStart] == '|'
        val trailing = line[trimmedEnd] == '|' && !isEscaped(line, trimmedEnd)
        return pipeCount + 1 - (if (leading) 1 else 0) - (if (trailing) 1 else 0)
    }

    private fun unescapedPipeCount(line: String): Int = line.indices.count { index ->
        line[index] == '|' && !isEscaped(line, index)
    }

    private fun nextUnescapedPipe(line: String, start: Int, endExclusive: Int): Int {
        for (index in start until endExclusive) {
            if (line[index] == '|' && !isEscaped(line, index)) return index
        }
        return -1
    }

    /** CommonMark punctuation escapes use an odd run of preceding backslashes. */
    private fun isEscaped(text: String, index: Int): Boolean {
        var cursor = index - 1
        var backslashes = 0
        while (cursor >= 0 && text[cursor] == '\\') {
            backslashes++
            cursor--
        }
        return backslashes % 2 == 1
    }

    private fun isTableDelimiterCell(line: String, start: Int, endExclusive: Int): Boolean {
        var cursor = start
        while (cursor < endExclusive && line[cursor].isWhitespace()) cursor++
        if (cursor < endExclusive && line[cursor] == ':') cursor++
        val dashStart = cursor
        while (cursor < endExclusive && line[cursor] == '-') cursor++
        if (cursor == dashStart) return false
        if (cursor < endExclusive && line[cursor] == ':') cursor++
        while (cursor < endExclusive && line[cursor].isWhitespace()) cursor++
        return cursor == endExclusive
    }

    /** 去掉表格所处的引用/列表容器前缀，让嵌套表格也进入同一预算。 */
    private fun stripTableContainerPrefix(line: String): String {
        var cursor = 0
        var changed: Boolean
        do {
            changed = false
            while (cursor < line.length && (line[cursor] == ' ' || line[cursor] == '\t')) cursor++
            if (cursor < line.length && line[cursor] == '>') {
                cursor++
                changed = true
                continue
            }

            val markerStart = cursor
            if (cursor < line.length && line[cursor] in "-+*") {
                cursor++
            } else {
                while (cursor < line.length && line[cursor].isDigit()) cursor++
                if (cursor == markerStart || cursor >= line.length || line[cursor] !in ".)") cursor = markerStart
                else cursor++
            }
            if (cursor > markerStart && cursor < line.length && line[cursor].isWhitespace()) {
                changed = true
            } else if (cursor > markerStart) {
                cursor = markerStart
            }
        } while (changed)
        return line.substring(cursor)
    }

    private fun fenceMarker(line: String): Fence? {
        val first = line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return null
        val character = line[first].takeIf { it == '`' || it == '~' } ?: return null
        var end = first
        while (end < line.length && line[end] == character) end++
        val length = end - first
        if (length < 3) return null
        return Fence(character, length, line.substring(end).isBlank())
    }

    private data class Fence(val character: Char, val length: Int, val closing: Boolean)

    private fun validateReply(body: ReplyBody): ReplyBody {
        requireIdentifier(body.replyToMsgId, "replyToMsgId")
        requireIdentifier(body.replyToSenderUid, "replyToSenderUid")
        requireOptionalLength(body.replyToSenderName, MAX_DISPLAY_NAME_LENGTH, "replyToSenderName")
        requireOptionalLength(body.replySnippet, MAX_SHORT_TEXT_LENGTH, "replySnippet")
        return body.copy(content = validateMarkdown(body.content))
    }

    private fun validateImage(body: ImageBody): ImageBody {
        validateAttachmentBody(body)
        requireDimension(body.width, "图片宽度")
        requireDimension(body.height, "图片高度")
        return body
    }

    private fun validateVoice(body: VoiceBody): VoiceBody {
        validateAttachmentBody(body)
        requireDuration(body.duration, "语音时长")
        return body
    }

    private fun validateVideo(body: VideoBody): VideoBody {
        validateAttachmentBody(body)
        requireDuration(body.duration, "视频时长")
        requireDimension(body.width, "视频宽度")
        requireDimension(body.height, "视频高度")
        return body
    }

    private fun validateSticker(body: StickerBody): StickerBody {
        validateAttachmentBody(body)
        requireDimension(body.width, "贴纸宽度")
        requireDimension(body.height, "贴纸高度")
        return body
    }

    private fun <T : AttachmentBody> validateAttachmentBody(body: T): T {
        validateAttachment(body.attachment)
        body.thumbnail?.let(::validateAttachment)
        return body
    }

    private fun validateAttachment(attachment: com.virjar.tk.model.Attachment) {
        require(attachment.path.isNotBlank() && attachment.path.length <= AttachmentPolicy.MAX_REFERENCE_LENGTH) {
            "附件路径非法或过长"
        }
        require(attachment.name.isNotBlank() && attachment.name.length <= AttachmentPolicy.MAX_NAME_LENGTH) {
            "附件名称非法或过长"
        }
        require(
            attachment.contentType.isNotBlank() &&
                attachment.contentType.length <= AttachmentPolicy.MAX_CONTENT_TYPE_LENGTH,
        ) { "附件类型非法或过长" }
        require(attachment.size >= 0) { "附件大小不能为负数" }
    }

    private fun validateLocation(body: LocationBody): LocationBody {
        require(body.latitude.isFinite() && body.latitude in -90.0..90.0) { "纬度非法" }
        require(body.longitude.isFinite() && body.longitude in -180.0..180.0) { "经度非法" }
        requireOptionalLength(body.title, MAX_DISPLAY_NAME_LENGTH, "位置标题")
        requireOptionalLength(body.address, MAX_SHORT_TEXT_LENGTH, "位置地址")
        return body
    }

    private fun validateContactCard(body: CardBody): CardBody {
        requireIdentifier(body.targetUid, "targetUid")
        require(body.targetName.isNotBlank() && body.targetName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "联系人名称非法或过长"
        }
        requireOptionalLength(body.targetAvatar, MAX_URL_LENGTH, "联系人头像")
        return body
    }

    private fun validateForward(body: ForwardBody): ForwardBody {
        body.forwardFromChatId?.let { requireIdentifier(it, "forwardFromChatId") }
        body.forwardFromMsgId?.let { requireIdentifier(it, "forwardFromMsgId") }
        body.forwardFromSenderUid?.let { requireIdentifier(it, "forwardFromSenderUid") }
        requireOptionalLength(body.forwardNote, MAX_SHORT_TEXT_LENGTH, "forwardNote")
        return body
    }

    private fun validateMergeForward(body: MergeForwardBody): MergeForwardBody {
        requireOptionalLength(body.title, MAX_DISPLAY_NAME_LENGTH, "合并转发标题")
        require(body.messageCount in 0..MAX_MERGE_FORWARD_MESSAGE_COUNT) { "合并转发消息数量非法" }
        return body
    }

    private fun validateReaction(body: ReactionBody): ReactionBody {
        requireIdentifier(body.targetMsgId, "targetMsgId")
        require(body.emoji.isNotBlank() && body.emoji.length <= MAX_EMOJI_LENGTH) { "Reaction 表情非法或过长" }
        require(body.action == 0 || body.action == 1) { "Reaction action 非法" }
        return body
    }

    private fun requireIdentifier(value: String, name: String): String {
        require(value.isNotBlank() && value.length <= MAX_IDENTIFIER_LENGTH) { "$name 非法或过长" }
        return value
    }

    private fun requireOptionalLength(value: String?, maximum: Int, name: String) {
        require(value == null || value.length <= maximum) { "$name 不能超过 $maximum 个字符" }
    }

    private fun requireDimension(value: Int, name: String) {
        require(value in 0..MAX_MEDIA_DIMENSION) { "$name 非法" }
    }

    private fun requireDuration(value: Int, name: String) {
        require(value in 0..MAX_MEDIA_DURATION_SECONDS) { "$name 非法" }
    }

    private fun validateInteractiveCard(body: InteractiveCardBody): InteractiveCardBody {
        require(body.payloadJson.length <= MAX_INTERACTIVE_CARD_JSON_LENGTH) {
            "交互卡片 JSON 不能超过 $MAX_INTERACTIVE_CARD_JSON_LENGTH 个字符"
        }
        val card = requireNotNull(body.toCard()) { "交互卡片 JSON 无法解析" }
        require(card.blocks.size <= MAX_INTERACTIVE_CARD_BLOCKS) {
            "交互卡片不能超过 $MAX_INTERACTIVE_CARD_BLOCKS 个内容块"
        }
        card.title?.let { title ->
            require(title.length <= MAX_INTERACTIVE_CARD_TEXT_LENGTH) { "交互卡片标题过长" }
        }
        card.blocks.forEach { block ->
            when (block) {
                is CardBlock.Text -> require(block.text.length <= MAX_INTERACTIVE_CARD_TEXT_LENGTH) {
                    "交互卡片文本块过长"
                }
            }
        }
        return body
    }

    @Suppress("DEPRECATION")
    fun typeOf(body: MessageBody): MessageType = when (body) {
        is TextBody -> MessageType.TEXT
        is RichTextBody -> MessageType.RICH_TEXT
        is InteractiveCardBody -> MessageType.INTERACTIVE_CARD
        is ImageBody -> MessageType.IMAGE
        is VoiceBody -> MessageType.VOICE
        is VideoBody -> MessageType.VIDEO
        is FileBody -> MessageType.FILE
        is LocationBody -> MessageType.LOCATION
        is CardBody -> MessageType.CARD
        is ReplyBody -> MessageType.REPLY
        is ForwardBody -> MessageType.FORWARD
        is MergeForwardBody -> MessageType.MERGE_FORWARD
        is RevokeBody -> MessageType.REVOKE
        is EditBody -> MessageType.EDIT
        is StickerBody -> MessageType.STICKER
        is ReactionBody -> MessageType.REACTION
    }

    private const val MARKDOWN_STRUCTURE_CHARACTERS = "#>*_~`[]()|!"
}

/** Markdown 源文本；旧 TextBody 只在兼容读取时进入这里。 */
@Suppress("DEPRECATION")
fun MessageBody?.markdownContentOrNull(): String? = when (this) {
    is RichTextBody -> markdown
    is TextBody -> text
    is ReplyBody -> content
    is EditBody -> newContent
    else -> null
}

/** 是否为可直接在 Markdown 编辑器中重新编辑的独立文本消息。 */
@Suppress("DEPRECATION")
fun MessageBody?.isMarkdownTextBody(): Boolean = this is RichTextBody || this is TextBody

/** 去除 Markdown 语法后的可检索/可预览文本。 */
@Suppress("DEPRECATION")
fun MessageBody?.plainTextContentOrNull(): String? = when (this) {
    is RichTextBody -> plainText
    is TextBody -> text
    is ReplyBody -> content.takeIf { it.isNotBlank() }?.let { buildRichTextBody(it).plainText }
    is EditBody -> buildRichTextBody(newContent).plainText
    else -> null
}
