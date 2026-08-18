package com.virjar.tk.e2e

import com.virjar.tk.rpc.gen.ChatRpcContract
import com.virjar.tk.rpc.gen.ContactRpcContract
import com.virjar.tk.rpc.gen.ConversationRpcContract
import com.virjar.tk.rpc.gen.MessageRpcContract
import com.virjar.tk.body.FileBody
import com.virjar.tk.body.ImageBody
import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.body.markdownContentOrNull
import com.virjar.tk.body.VideoBody
import com.virjar.tk.body.VoiceBody
import com.virjar.tk.client.ConnectionState
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Message
import com.virjar.tk.model.Attachment
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.MessageAckPayload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.virjar.tk.repository.FileRepository
import com.virjar.tk.repository.UploadResult
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.*
import java.util.UUID

/**
 * 对端测试角色（账号 B）—— 真机 UI 测试的协同脚本。
 *
 * 真机 UI 操作账号 A，本脚本扮演账号 B（注册/登录/收发消息/接受好友），
 * 覆盖好友/消息/群等双账户场景。复用 [RemoteAcceptanceSupport] 连接真实部署。
 *
 * 使用方式见 doc/android-test-guide.md「对端脚本」章节。
 *
 * 媒体消息（sendFileAsB / sendImageAsB / sendVoiceAsB / sendVideoAsB）：
 * 通过 -Dpeer.file=<本地文件路径> 指定文件，脚本自动上传到服务器再发送消息。
 * 所有文件资源收敛到 im.virjar.com，不依赖外部 URL。
 */
@EnabledIfSystemProperty(named = "tk.e2e.remote", matches = "true")
class TestPeer {

    /** 服务器地址，通过系统属性或默认值 */
    private val serverUrl: String get() = System.getProperty("peer.server") ?: "https://im.virjar.com"

    // ──────────────── 工具方法 ────────────────

    /** 上传并解析服务端媒体描述符。 */
    private suspend fun uploadFileMeta(file: File, accessToken: String?, mimeType: String): UploadResult =
        FileRepository(serverUrl, accessToken)
            .uploadWithMeta(file.readBytes(), file.name, mimeType)
            .getOrThrow()

    /**
     * 上传本地文件到服务器，返回权威附件描述符。
     * 文件上传接口走 Bearer accessToken 鉴权（FileRoutes），必须带认证头。
     */
    private suspend fun uploadFile(file: File, accessToken: String?, mimeType: String = "application/octet-stream"): Attachment =
        FileRepository(serverUrl, accessToken)
            .upload(file.readBytes(), file.name, mimeType)
            .getOrThrow()

    // ──────────────── 测试方法 ────────────────

    @org.junit.jupiter.api.Test
    fun registerPeer() = runBlocking {
        val suffix = System.getProperty("peer.arg") ?: "peer"
        val session = RemoteAcceptanceSupport.registerUser(suffix)
        // registerUser 内部注册的 username 是 "e2e-$suffix-<8hex>" 格式，
        // 这里必须输出真实 username，否则后续 loginUser 用错误的 username 登录会失败。
        val username = session.registeredUsername ?: "e2e-$suffix-UNKNOWN"
        println("===PEER_REGISTERED===")
        println("username=$username")
        println("uid=${session.uid}")
        println("===END===")
        session.close()
    }

    /**
     * 登录任意账号打印其 uid。用法：
     *   -Dpeer.username=<user> (-Dpeer.password=<pass>，默认 password123)
     */
    @org.junit.jupiter.api.Test
    fun printToken() = runBlocking {
        val username = System.getProperty("peer.username") ?: return@runBlocking
        val password = System.getProperty("peer.password") ?: "password123"
        val session = RemoteAcceptanceSupport.loginUser(username, password)
        println("===TOKEN=== ${session.userSession.accessToken}")
        session.close()
    }

    @org.junit.jupiter.api.Test
    fun whoami() = runBlocking {
        val username = System.getProperty("peer.username") ?: run {
            println("[TestPeer] whoami 缺少 username"); return@runBlocking
        }
        val password = System.getProperty("peer.password") ?: "password123"
        val session = RemoteAcceptanceSupport.loginUser(username, password)
        println("===WHOAMI=== username=$username uid=${session.uid}")
        session.close()
    }

    @org.junit.jupiter.api.Test
    fun acceptFriend() = runBlocking {
        val username = System.getProperty("peer.username") ?: "peer"
        val token = System.getProperty("peer.arg") ?: run {
            println("[TestPeer] accept 缺少 token"); return@runBlocking
        }
        val session = RemoteAcceptanceSupport.loginUser(username, "password123")
        val resp = session.invoke("contact", ContactRpcContract.M_ACCEPT,
            ProtoCodec.encodePayload { writeString(token) })
        println("===ACCEPT ${if (resp.status == 0) "SUCCESS" else "FAILED"}=== status=${resp.status}")
        session.close()
    }

    /**
     * 自动接受最新一条待处理好友申请。用法：
     *   -Dpeer.username=<B>
     *
     * 登录 B 后调用 LIST_APPLIES 取第一条 pending 申请的 token，再 ACCEPT。
     * 适用于对端无法预知 token 的 UI 协同测试场景。
     *
     * 注：之前服务端 listPendingApplies 不下发 token 字段，已修复（补上 token 查询）。
     */
    @org.junit.jupiter.api.Test
    fun acceptLatestFriend() = runBlocking {
        val username = System.getProperty("peer.username") ?: run {
            println("[TestPeer] acceptLatest 缺少 username"); return@runBlocking
        }
        val session = RemoteAcceptanceSupport.loginUser(username, "password123")
        val listResp = session.invoke("contact", ContactRpcContract.M_LIST_APPLIES)
        val listPayload = listResp.payload
        if (listResp.status != 0 || listPayload == null) {
            println("===ACCEPT FAILED=== 无法获取申请列表 status=${listResp.status}")
            session.close(); return@runBlocking
        }
        val applies = ProtoCodec.decodeList(com.virjar.tk.model.ContactApply, listPayload)
        val pending = applies.firstOrNull { it.status == 0 && it.token != null }
        if (pending == null) {
            println("===ACCEPT FAILED=== 没有可接受的申请（共 ${applies.size} 条，可能 token 未下发）")
            session.close(); return@runBlocking
        }
        println("[TestPeer] 待处理申请 from=${pending.fromUser?.name ?: pending.fromUid} token=${pending.token}")
        val resp = session.invoke("contact", ContactRpcContract.M_ACCEPT,
            ProtoCodec.encodePayload { writeString(pending.token) })
        println("===ACCEPT ${if (resp.status == 0) "SUCCESS" else "FAILED"}=== status=${resp.status} fromUid=${pending.fromUid}")
        session.close()
    }

    /**
     * 创建个人聊天。用法：
     *   -Dpeer.username=<B> -Dpeer.arg=<targetUid(A的uid)>
     */
    @org.junit.jupiter.api.Test
    fun createPersonalChat() = runBlocking {
        val username = System.getProperty("peer.username") ?: return@runBlocking
        val targetUid = System.getProperty("peer.arg") ?: return@runBlocking
        val session = RemoteAcceptanceSupport.loginUser(username, "password123")
        val resp = session.invoke("chat", ChatRpcContract.M_CREATE_PERSONAL,
            ProtoCodec.encodePayload { writeString(targetUid) })
        val payload = resp.payload
        val chatId = if (resp.status == 0 && payload != null) {
            ProtoCodec.decode(com.virjar.tk.model.Chat, payload).chatId
        } else ""
        println("===CREATE_CHAT ${if (resp.status == 0) "SUCCESS" else "FAILED"}=== chatId=$chatId")
        session.close()
    }

    /**
     * B 一键为 A 准备会话列表数据：建群（拉 A）+ 发起私聊（发一条消息）。
     *
     * 用途：UI 角标/区分验证场景——让 A 的会话列表同时出现群聊和私聊。
     * 用法：
     *   -Dpeer.username=<B> -Dpeer.arg=<A的uid>:<群名>
     *
     * 两个会话都会通过 NOTIFY 推送给 A，A 端本地优先架构自动同步。
     */
    @org.junit.jupiter.api.Test
    fun setupGroupAndPrivate() = runBlocking {
        val username = System.getProperty("peer.username") ?: run {
            println("[TestPeer] setup 缺少 username"); return@runBlocking
        }
        val arg = System.getProperty("peer.arg") ?: run {
            println("[TestPeer] setup 缺少 arg (aUid:groupName)"); return@runBlocking
        }
        val (aUid, groupName) = arg.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "测试群" } }
        val session = RemoteAcceptanceSupport.loginUser(username, "password123")

        // 1. B 建群拉 A
        val groupResp = session.invoke("chat", ChatRpcContract.M_CREATE_GROUP,
            ProtoCodec.encodePayload {
                writeString(groupName)
                writeString(null)
                writeVarInt(1)
                writeString(aUid)
            })
        val groupPayload = groupResp.payload
        val groupChatId = if (groupResp.status == 0 && groupPayload != null) {
            ProtoCodec.decode(com.virjar.tk.model.Chat, groupPayload).chatId
        } else ""
        println("===CREATE_GROUP ${if (groupResp.status == 0) "SUCCESS" else "FAILED"}=== chatId=$groupChatId")

        // 2. B 发起私聊（CREATE_PERSONAL 与 A），再发一条消息
        val pcResp = session.invoke("chat", ChatRpcContract.M_CREATE_PERSONAL,
            ProtoCodec.encodePayload { writeString(aUid) })
        val pcPayload = pcResp.payload
        val pcChatId = if (pcResp.status == 0 && pcPayload != null) {
            ProtoCodec.decode(com.virjar.tk.model.Chat, pcPayload).chatId
        } else ""
        if (pcChatId.isNotEmpty()) {
            val msg = Message(chatId = pcChatId, clientMsgId = UUID.randomUUID().toString(),
                messageType = MessageType.RICH_TEXT.code, timestamp = System.currentTimeMillis(),
                senderUid = session.uid, body = buildRichTextBody("你好，这是私聊消息"))
            session.imClient.sendAndWaitAck(msg)
        }
        println("===CREATE_PERSONAL ${if (pcResp.status == 0) "SUCCESS" else "FAILED"}=== chatId=$pcChatId")
        println("===SETUP_DONE=== group=$groupChatId personal=$pcChatId")
        session.close()
    }

    @org.junit.jupiter.api.Test
    fun sendMsgAsB() = runBlocking {
        val username = System.getProperty("peer.username") ?: run {
            println("[TestPeer] sendMsg 缺少 username"); return@runBlocking
        }
        val arg = System.getProperty("peer.arg") ?: run {
            println("[TestPeer] sendMsg 缺少 arg (chatId:text)"); return@runBlocking
        }
        val (chatId, text) = arg.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "hello from B" } }
        val session = RemoteAcceptanceSupport.loginUser(username, "password123")
        val msg = Message(chatId = chatId, clientMsgId = UUID.randomUUID().toString(),
            messageType = MessageType.RICH_TEXT.code, timestamp = System.currentTimeMillis(),
            senderUid = session.uid, body = buildRichTextBody(text))
        val ack = session.imClient.sendAndWaitAck(msg)
        println("===SEND_MSG ${if (ack.code == 0) "SUCCESS" else "FAILED"}=== ack=${ack.code} seq=${ack.serverSeq}")
        session.close()
    }

    /**
     * 发文件消息。用法：
     *   -Dpeer.username=<B> -Dpeer.arg=<chatId> -Dpeer.file=<本地文件路径>
     * 输出：===SEND_FILE SUCCESS/FAILED=== ack=<code>
     */
    @org.junit.jupiter.api.Test
    fun sendFile() = runBlocking {
        val username = System.getProperty("peer.username") ?: return@runBlocking
        val chatId = System.getProperty("peer.arg") ?: return@runBlocking
        val file = File(System.getProperty("peer.file") ?: return@runBlocking)
        if (!file.exists()) { println("[TestPeer] file not found: ${file.absolutePath}"); return@runBlocking }

        val session = RemoteAcceptanceSupport.loginUser(username, "password123")
        val attachment = uploadFile(file, session.userSession.accessToken)
        val msg = Message(chatId = chatId, clientMsgId = UUID.randomUUID().toString(),
            messageType = MessageType.FILE.code, timestamp = System.currentTimeMillis(),
            senderUid = session.uid, body = FileBody(attachment))
        val ack = session.imClient.sendAndWaitAck(msg)
        println("===SEND_FILE ${if (ack.code == 0) "SUCCESS" else "FAILED"}=== ack=${ack.code}")
        session.close()
    }

    /**
     * 接收检查：B 获取与 targetUid(A) 的会话中最后一条消息内容。
     * 用法：-Dpeer.username=<B> -Dpeer.arg=<A的uid>
     * 输出：===RECV_CHECK=== text=<最后一条消息文本> from=<senderUid>
     */
    @org.junit.jupiter.api.Test
    fun recvCheck() = runBlocking {
        val username = System.getProperty("peer.username") ?: run {
            println("[TestPeer] recvCheck 缺少 username"); return@runBlocking
        }
        val targetUid = System.getProperty("peer.arg") ?: run {
            println("[TestPeer] recvCheck 缺少 arg (targetUid)"); return@runBlocking
        }
        val session = RemoteAcceptanceSupport.loginUser(username, "password123")
        // 列出会话（ConversationService.LIST，非 ChatMethod）
        val listResp = session.invoke("conversation", ConversationRpcContract.M_LIST)
        val listPayload = listResp.payload
        if (listResp.status != 0 || listPayload == null) {
            println("===RECV_CHECK FAILED=== listConversations status=${listResp.status}")
            session.close(); return@runBlocking
        }
        val conversations = ProtoCodec.decodeList(com.virjar.tk.model.Conversation, listPayload)
        // 找与目标 uid 的私聊：按成员精确定位（chatId 是 UUID，不含 uid——旧"chatId 含 uid"
        // 约定从未成立，此处改为 GET_MEMBERS 对比成员集）
        val conv = conversations.firstOrNull { c ->
            if (c.chatType != 1) return@firstOrNull false
            val membersResp = session.invoke("chat", ChatRpcContract.M_GET_MEMBERS,
                ProtoCodec.encodePayload { writeString(c.chatId) })
            membersResp.status == 0 && membersResp.payload?.let { pl ->
                ProtoCodec.decodeList(com.virjar.tk.model.Member, pl).map { it.uid }.toSet() == setOf(targetUid, session.uid)
            } ?: false
        }
        if (conv == null) {
            println("===RECV_CHECK FAILED=== 未找到 ${targetUid.take(12)} 的会话")
            session.close(); return@runBlocking
        }
        println("===RECV_CHECK=== text=${conv.lastMessage ?: "(empty)"} unread=${conv.unreadCount} chatId=${conv.chatId.take(12)}")
        val historyResp = session.invoke(
            "message",
            MessageRpcContract.M_GET_HISTORY,
            ProtoCodec.encodePayload {
                writeString(conv.chatId)
                writeVarLong(0)
                writeVarInt(1)
            },
        )
        val latest = historyResp.payload
            ?.takeIf { historyResp.status == 0 }
            ?.let { ProtoCodec.decodeList(Message, it).firstOrNull() }
        val markdownBase64 = latest?.body?.markdownContentOrNull()
            ?.let { java.util.Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
            .orEmpty()
        println("===RECV_MESSAGE=== type=${latest?.messageType ?: -1} markdownBase64=$markdownBase64")
        session.close()
    }

    /**
     * 通过真实 RPC 转发一条已存在的消息，并打印服务端返回的目标会话和序号。
     * 用法：-Dpeer.username=<user> -Dpeer.arg=<srcChatId>:<srcSeq>:<targetChatId>
     */
    @org.junit.jupiter.api.Test
    fun forwardMessage() = runBlocking {
        val username = System.getProperty("peer.username") ?: run {
            println("[TestPeer] forwardMessage 缺少 username"); return@runBlocking
        }
        val arg = System.getProperty("peer.arg") ?: run {
            println("[TestPeer] forwardMessage 缺少 arg (srcChatId:srcSeq:targetChatId)"); return@runBlocking
        }
        val parts = arg.split(":", limit = 3)
        if (parts.size != 3) {
            println("===FORWARD FAILED=== 参数格式错误")
            return@runBlocking
        }
        val (srcChatId, srcSeqText, targetChatId) = parts
        val srcSeq = srcSeqText.toLongOrNull() ?: run {
            println("===FORWARD FAILED=== srcSeq 不是数字")
            return@runBlocking
        }
        val password = System.getProperty("peer.password") ?: "password123"
        val session = RemoteAcceptanceSupport.loginUser(username, password)
        val resp = session.invoke(
            "message",
            MessageRpcContract.M_FORWARD,
            MessageRpcContract.encodeForward(srcChatId, srcSeq, targetChatId),
        )
        val forwarded = resp.payload?.takeIf { resp.status == 0 }?.let { ProtoCodec.decode(Message, it) }
        println(
            "===FORWARD ${if (resp.status == 0) "SUCCESS" else "FAILED"}=== " +
                "status=${resp.status} chatId=${forwarded?.chatId.orEmpty()} " +
                "seq=${forwarded?.serverSeq ?: 0} clientMsgId=${forwarded?.clientMsgId.orEmpty()}",
        )
        session.close()
    }

    /**
     * B 给 A 发消息（一键：创建私聊+发送）。用法：
     *   -Dpeer.username=<B> -Dpeer.arg=<A的uid>:<消息文本>
     * 输出：===SEND_TO=== chatId=xxx SUCCESS
     */
    @org.junit.jupiter.api.Test
    fun sendMsgTo() = runBlocking {
        val username = System.getProperty("peer.username") ?: run {
            println("[TestPeer] sendMsgTo 缺少 username"); return@runBlocking
        }
        val arg = System.getProperty("peer.arg") ?: run {
            println("[TestPeer] sendMsgTo 缺少 arg (targetUid:text)"); return@runBlocking
        }
        val (targetUid, text) = arg.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "hello from B" } }
        val session = RemoteAcceptanceSupport.loginUser(username, "password123")

        // 创建私聊
        val pcResp = session.invoke("chat", ChatRpcContract.M_CREATE_PERSONAL,
            ProtoCodec.encodePayload { writeString(targetUid) })
        val pcPayload = pcResp.payload
        val chatId = if (pcResp.status == 0 && pcPayload != null) {
            ProtoCodec.decode(com.virjar.tk.model.Chat, pcPayload).chatId
        } else ""
        if (chatId.isEmpty()) {
            println("===SEND_TO FAILED=== create chat failed status=${pcResp.status}")
            session.close(); return@runBlocking
        }

        // 发消息
        val msg = Message(chatId = chatId, clientMsgId = UUID.randomUUID().toString(),
            messageType = MessageType.RICH_TEXT.code, timestamp = System.currentTimeMillis(),
            senderUid = session.uid, body = buildRichTextBody(text))
        val ack = session.imClient.sendAndWaitAck(msg)
        println("===SEND_TO ${if (ack.code == 0) "SUCCESS" else "FAILED"}=== chatId=$chatId ack=${ack.code}")
        session.close()
    }

    /**
     * 生成本地测试文件（图标/音频/视频最小合法文件）。
     * 用法：-Dpeer.arg=<类型:png|mp3|mp4>
     * 输出：===GEN_FILE=== path=/tmp/test.xxx
     */
    @org.junit.jupiter.api.Test
    fun generateTestFile() = runBlocking {
        val type = System.getProperty("peer.arg") ?: "png"
        // 优先用 src/test/resources/media 下的真实测试资源（可渲染/播放），
        // 找不到则 fallback 到 createMinimalXxx（最小合法字节流）。
        val resName = when (type) {
            "png" -> "/media/test_image.png"
            "jpg" -> "/media/test_image.jpg"
            "mp3" -> "/media/test_voice.mp3"
            "mp4" -> "/media/test_video.mp4"
            "txt" -> "/media/test_doc.txt"
            else -> null
        }
        val file = if (resName != null) {
            loadResourceToFile(resName) ?: run {
                println("===GEN_FILE FAILED=== 资源不存在: $resName，尝试最小文件")
                when (type) {
                    "png", "jpg" -> createMinimalPng()
                    "mp3" -> createMinimalMp3()
                    "mp4" -> createMinimalMp4()
                    else -> { println("===GEN_FILE FAILED=== unknown type '$type'"); return@runBlocking }
                }
            }
        } else {
            println("===GEN_FILE FAILED=== unknown type '$type'"); return@runBlocking
        }
        println("===GEN_FILE=== path=${file.absolutePath} size=${file.length()}")
    }

    /** 从 classpath 读取测试资源到临时文件。 */
    private fun loadResourceToFile(resourcePath: String): java.io.File? {
        val stream = TestPeer::class.java.getResourceAsStream(resourcePath) ?: return null
        val ext = resourcePath.substringAfterLast(".")
        val file = java.io.File.createTempFile("tt_media_", ".$ext").also { it.deleteOnExit() }
        stream.use { it.copyTo(file.outputStream()) }
        return file
    }

    private fun createMinimalPng(): File {
        // 最小合法 PNG: 8字节签名 + IHDR + IDAT + IEND
        val f = File.createTempFile("test_", ".png").also { it.deleteOnExit() }
        val w = 1; val h = 1
        DataOutputStream(FileOutputStream(f)).use { dos ->
            dos.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) // PNG signature
            writePngChunk(dos, "IHDR", run {
                val bb = java.nio.ByteBuffer.allocate(13)
                bb.putInt(w); bb.putInt(h); bb.put(8); bb.put(2); bb.put(0); bb.put(0); bb.put(0)
                bb.array()
            })
            writePngChunk(dos, "IDAT", run {
                val deflater = java.util.zip.Deflater()
                deflater.setInput(byteArrayOf(0, 0, 0, 0, 0))
                deflater.finish()
                val out = ByteArray(64)
                val len = deflater.deflate(out)
                out.copyOf(len)
            })
            writePngChunk(dos, "IEND", ByteArray(0))
        }
        return f
    }

    private fun writePngChunk(dos: DataOutputStream, type: String, data: ByteArray) {
        dos.writeInt(data.size)
        dos.writeBytes(type)
        dos.write(data)
        val crc = java.util.zip.CRC32()
        crc.update(type.toByteArray()); crc.update(data)
        dos.writeInt(crc.value.toInt())
    }

    private fun createMinimalMp3(): File {
        // 最小合法 MP3: ID3v2 header + 1 valid frame
        val f = File.createTempFile("test_", ".mp3").also { it.deleteOnExit() }
        FileOutputStream(f).use { fos ->
            // ID3v2 header (10 bytes, no tags)
            fos.write(byteArrayOf(73, 68, 51, 3, 0, 0, 0, 0, 0, 0))
            // MPEG audio frame header (4 bytes): MPEG1 Layer3 128kbps 44100Hz
            fos.write(byteArrayOf(-1, -5, -112, 64))
            // Frame data (minimal - silence)
            fos.write(ByteArray(417))
        }
        return f
    }

    private fun createMinimalMp4(): File {
        // 最小合法 MP4: ftyp + moov box
        val f = File.createTempFile("test_", ".mp4").also { it.deleteOnExit() }
        DataOutputStream(FileOutputStream(f)).use { dos ->
            // ftyp box
            val ftyp = java.nio.ByteBuffer.allocate(20)
            ftyp.putInt(20); ftyp.put("ftyp".toByteArray()); ftyp.put("isom".toByteArray())
            ftyp.putInt(0); ftyp.put("isom".toByteArray())
            dos.write(ftyp.array())
            // moov box (empty)
            dos.writeInt(8); dos.writeBytes("moov")
        }
        return f
    }

    /**
     * 发图片消息。用法：
     *   -Dpeer.username=<B> -Dpeer.arg=<chatId> -Dpeer.file=<本地图片路径>
     */
    @org.junit.jupiter.api.Test
    fun sendImageAsB() = runBlocking {
        val username = System.getProperty("peer.username") ?: return@runBlocking
        val chatId = System.getProperty("peer.arg") ?: return@runBlocking
        val file = File(System.getProperty("peer.file") ?: return@runBlocking)
        if (!file.exists()) { println("[TestPeer] file not found: ${file.absolutePath}"); return@runBlocking }

        val session = RemoteAcceptanceSupport.loginUser(username, "password123")
        val upload = uploadFileMeta(file, session.userSession.accessToken, "image/png")
        val msg = Message(chatId = chatId, clientMsgId = UUID.randomUUID().toString(),
            messageType = MessageType.IMAGE.code, timestamp = System.currentTimeMillis(),
            senderUid = session.uid, body = ImageBody(upload.file, thumbnail = upload.thumbnail))
        val ack = session.imClient.sendAndWaitAck(msg)
        println("===SEND_IMAGE ${if (ack.code == 0) "SUCCESS" else "FAILED"}=== ack=${ack.code} thumb=${upload.thumbnail != null}")
        session.close()
    }

    /**
     * 发交互卡片消息。用法：
     *   -Dpeer.username=<B> -Dpeer.arg=<chatId>
     */
    @org.junit.jupiter.api.Test
    fun sendCardAsB() = runBlocking {
        val username = System.getProperty("peer.username") ?: return@runBlocking
        val chatId = System.getProperty("peer.arg") ?: return@runBlocking
        val session = RemoteAcceptanceSupport.loginUser(username, "password123")
        val card = com.virjar.tk.body.CardPayload(
            title = "构建通知",
            blocks = listOf(
                com.virjar.tk.body.CardBlock.Text("分支 main 构建通过，耗时 3m12s"),
                com.virjar.tk.body.CardBlock.Text("提交：飞书风格设计系统落地"),
            ),
        )
        val msg = Message(chatId = chatId, clientMsgId = UUID.randomUUID().toString(),
            messageType = MessageType.INTERACTIVE_CARD.code, timestamp = System.currentTimeMillis(),
            senderUid = session.uid, body = com.virjar.tk.body.InteractiveCardBody.of(card))
        val ack = session.imClient.sendAndWaitAck(msg)
        println("===SEND_CARD ${if (ack.code == 0) "SUCCESS" else "FAILED"}=== ack=${ack.code}")
        session.close()
    }

    /**
     * 发语音消息。用法：
     *   -Dpeer.username=<B> -Dpeer.arg=<chatId> -Dpeer.file=<本地音频路径>
     */
    @org.junit.jupiter.api.Test
    fun sendVoiceAsB() = runBlocking {
        val username = System.getProperty("peer.username") ?: return@runBlocking
        val chatId = System.getProperty("peer.arg") ?: return@runBlocking
        val file = File(System.getProperty("peer.file") ?: return@runBlocking)
        if (!file.exists()) { println("[TestPeer] file not found: ${file.absolutePath}"); return@runBlocking }

        val session = RemoteAcceptanceSupport.loginUser(username, "password123")
        val attachment = uploadFile(file, session.userSession.accessToken, "audio/aac")
        val msg = Message(chatId = chatId, clientMsgId = UUID.randomUUID().toString(),
            messageType = MessageType.VOICE.code, timestamp = System.currentTimeMillis(),
            senderUid = session.uid, body = VoiceBody(attachment, duration = 12))
        val ack = session.imClient.sendAndWaitAck(msg)
        println("===SEND_VOICE ${if (ack.code == 0) "SUCCESS" else "FAILED"}=== ack=${ack.code}")
        session.close()
    }

    /**
     * 发视频消息。用法：
     *   -Dpeer.username=<B> -Dpeer.arg=<chatId> -Dpeer.file=<本地视频路径>
     */
    @org.junit.jupiter.api.Test
    fun sendVideoAsB() = runBlocking {
        val username = System.getProperty("peer.username") ?: return@runBlocking
        val chatId = System.getProperty("peer.arg") ?: return@runBlocking
        val file = File(System.getProperty("peer.file") ?: return@runBlocking)
        if (!file.exists()) { println("[TestPeer] file not found: ${file.absolutePath}"); return@runBlocking }

        val session = RemoteAcceptanceSupport.loginUser(username, "password123")
        val upload = uploadFileMeta(file, session.userSession.accessToken, "video/mp4")
        val msg = Message(chatId = chatId, clientMsgId = UUID.randomUUID().toString(),
            messageType = MessageType.VIDEO.code, timestamp = System.currentTimeMillis(),
            senderUid = session.uid,
            body = VideoBody(upload.file, duration = 5, width = 960, height = 540, thumbnail = upload.thumbnail))
        val ack = session.imClient.sendAndWaitAck(msg)
        println("===SEND_VIDEO ${if (ack.code == 0) "SUCCESS" else "FAILED"}=== ack=${ack.code} thumb=${upload.thumbnail != null}")
        session.close()
    }
}
