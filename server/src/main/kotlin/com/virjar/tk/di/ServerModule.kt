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
    single { ChatService(get(), get(), get()) }
    single { ConversationService(get(), get(), get()) }
    single { MessageService(get(), get(), get(), get(), get()) }
    single { PresenceService(get(), get()) }
    single { HealthChecker(get(), get(), get()) }

    // RPC Route Handlers
    single { UserRouteHandler(get()) }
    single { ContactRouteHandler(get()) }
    single { ChatRouteHandler(get()) }
    single { MessageRouteHandler(get(), get()) }
    single { ConversationRouteHandler(get()) }
    single { DeviceRouteHandler(get(), get()) }
    single { AuthRouteHandler(get(), get()) }
    single { GenericRouteHandler() }
    single { RpcDispatcher(get(), get(), get(), get(), get(), get(), get(), get()) }

    // TCP Server
    single { TcpServer() }
}

/** 生产环境使用默认 Environment 路径 */
val serverModule = createServerModule()
