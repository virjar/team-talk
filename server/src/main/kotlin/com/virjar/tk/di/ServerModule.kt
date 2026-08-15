package com.virjar.tk.di

import com.virjar.tk.domain.auth.AuthService
import com.virjar.tk.domain.auth.TokenStore
import com.virjar.tk.domain.chat.ChatMemberRepository
import com.virjar.tk.domain.chat.ChatRepository
import com.virjar.tk.domain.chat.ChatService
import com.virjar.tk.domain.chat.ChatStore
import com.virjar.tk.domain.chat.InviteLinkRepository
import com.virjar.tk.domain.contact.ContactRepository
import com.virjar.tk.domain.contact.ContactService
import com.virjar.tk.domain.contact.ContactStore
import com.virjar.tk.domain.conversation.ConversationRepository
import com.virjar.tk.domain.conversation.ConversationService
import com.virjar.tk.domain.device.DeviceRepository
import com.virjar.tk.domain.health.HealthChecker
import com.virjar.tk.domain.message.MessageService
import com.virjar.tk.domain.presence.PresenceService
import com.virjar.tk.domain.user.UserRepository
import com.virjar.tk.domain.user.UserService
import com.virjar.tk.protocol.rpc.RpcStubRegistry
import com.virjar.tk.rpc.gen.UserRpcContract
import com.virjar.tk.rpc.gen.AuthRpcContract
import com.virjar.tk.rpc.gen.ContactRpcContract
import com.virjar.tk.rpc.gen.ChatRpcContract
import com.virjar.tk.rpc.gen.MessageRpcContract
import com.virjar.tk.rpc.gen.ConversationRpcContract
import com.virjar.tk.rpc.gen.DeviceRpcContract
import com.virjar.tk.protocol.rpc.UserRpcImpl
import com.virjar.tk.protocol.rpc.AuthRpcImpl
import com.virjar.tk.protocol.rpc.ContactRpcImpl
import com.virjar.tk.protocol.rpc.ChatRpcImpl
import com.virjar.tk.protocol.rpc.MessageRpcImpl
import com.virjar.tk.protocol.rpc.ConversationRpcImpl
import com.virjar.tk.protocol.rpc.DeviceRpcImpl
import com.virjar.tk.domain.user.UserStore
import com.virjar.tk.env.Environment
import com.virjar.tk.infra.search.SearchIndex
import com.virjar.tk.infra.storage.ClientLogStore
import com.virjar.tk.infra.storage.FileStore
import com.virjar.tk.infra.storage.MessageStore
import com.virjar.tk.infra.sync.ClientRegistry
import com.virjar.tk.infra.sync.SyncEventService
import com.virjar.tk.protocol.TcpServer
import com.virjar.tk.protocol.dispatcher.*
import org.koin.dsl.module
import java.io.File

fun createServerModule(
    tokenStorePath: String = Environment.tokenStoreDir.absolutePath,
    messageStorePath: String = Environment.rocksdbDir.absolutePath,
    searchIndexPath: File = Environment.luceneIndexDir,
    fileStoreDbPath: String = Environment.fileStoreRocksdbDir.absolutePath,
    fileStoreFsPath: String = Environment.fileStoreFsDir.absolutePath,
) = module {
    // 基础设施 — 使用参数替代 Environment
    single { TokenStore(tokenStorePath) }
    single { ClientRegistry() }
    single { MessageStore(messageStorePath) }
    single { FileStore(fileStoreDbPath, fileStoreFsPath) }
    single { SearchIndex(searchIndexPath) }
    single { ClientLogStore() }

    // Repository（纯 DB 访问）
    single { UserRepository() }
    single { ContactRepository(get()) }
    single { ChatRepository() }
    single { ChatMemberRepository() }
    single { InviteLinkRepository() }
    single { ConversationRepository(get(), get(), get()) }
    single { DeviceRepository() }

    // Store（热缓存 + 异步写入，包装 Repository）
    single { UserStore(get()) }
    single { ContactStore(get()) }
    single { ChatStore(get(), get(), get()) }

    // Domain Service
    single { UserService(get(), get()) }
    single { AuthService(get(), get()) }
    single { ContactService(get(), get()) }
    single { SyncEventService(get()) }
    single { ChatService(get(), get(), get(), get()) }
    single { ConversationService(get(), get(), get()) }
    single { MessageService(get(), get(), get(), get(), get()) }
    single { PresenceService(get(), get()) }
    single { HealthChecker(get(), get(), get()) }

    // RPC 注册表（IDL 生成 Stub + 薄壳 Impl；serviceId 字符串注册）
    single {
        RpcStubRegistry().apply {
            register(UserRpcContract.SERVICE) { uid -> UserRpcImpl(uid, get()) }
            register(AuthRpcContract.SERVICE) { uid -> AuthRpcImpl(uid, get(), get()) }
            register(ContactRpcContract.SERVICE) { uid -> ContactRpcImpl(uid, get()) }
            register(ChatRpcContract.SERVICE) { uid -> ChatRpcImpl(uid, get()) }
            register(MessageRpcContract.SERVICE) { uid -> MessageRpcImpl(uid, get(), get()) }
            register(ConversationRpcContract.SERVICE) { uid -> ConversationRpcImpl(uid, get()) }
            register(DeviceRpcContract.SERVICE) { uid -> DeviceRpcImpl(uid, get(), get()) }
        }
    }
    single { RpcDispatcher(get()) }

    // TCP Server
    single { TcpServer() }
}

/** 生产环境使用默认 Environment 路径 */
val serverModule = createServerModule()
