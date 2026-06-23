package com.virjar.tk.integration

import com.virjar.tk.domain.auth.AuthService
import com.virjar.tk.domain.auth.TokenStore
import com.virjar.tk.domain.chat.ChatRepository
import com.virjar.tk.domain.chat.ChatService
import com.virjar.tk.domain.contact.ContactRepository
import com.virjar.tk.domain.contact.ContactService
import com.virjar.tk.domain.conversation.ConversationRepository
import com.virjar.tk.domain.conversation.ConversationService
import com.virjar.tk.domain.device.DeviceRepository
import com.virjar.tk.domain.message.MessageService
import com.virjar.tk.domain.user.UserRepository
import com.virjar.tk.domain.user.UserService
import com.virjar.tk.di.createServerModule
import com.virjar.tk.infra.db.DatabaseFactory
import com.virjar.tk.infra.search.SearchIndex
import com.virjar.tk.infra.storage.MessageStore
import com.virjar.tk.infra.sync.ClientRegistry
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.koin.dsl.koinApplication
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private val testRunId = System.nanoTime()
private val counter = AtomicInteger(0)
fun uniqueUsername(base: String): String = "${base}-${testRunId}-${counter.incrementAndGet()}"

/**
 * 测试环境容器。
 * 使用 Embedded PostgreSQL + Koin 容器，完全隔离，测试结束自动清理所有临时文件。
 */
class TestEnvironment : AutoCloseable {
    private val testId = UUID.randomUUID().toString()
    private val testRoot = File("/tmp/tk-test-${testId}")

    private val tokensDir = File(testRoot, "tokens")
    private val msgsDir = File(testRoot, "msgs")
    private val searchDir = File(testRoot, "search")
    private val fileStoreDir = File(testRoot, "file-store")

    // Embedded PG（随机端口，完全隔离）
    private val embeddedPg = EmbeddedPostgres.builder().start()

    // Koin 容器（独立实例，不污染全局）
    private val koinApp = koinApplication {
        modules(createServerModule(
            tokenStorePath = tokensDir.absolutePath,
            messageStorePath = msgsDir.absolutePath,
            searchIndexPath = searchDir,
            fileStoreDbPath = File(fileStoreDir, "rocksdb").absolutePath,
            fileStoreFsPath = File(fileStoreDir, "files").absolutePath,
        ))
    }
    private val koin = koinApp.koin

    init {
        testRoot.mkdirs()
        DatabaseFactory.create(
            jdbcUrl = embeddedPg.getJdbcUrl("postgres", "postgres"),
            user = "postgres",
            password = "postgres",
        )
        koin.get<MessageStore>().init()
        koin.get<SearchIndex>().start()
        koin.get<com.virjar.tk.infra.storage.FileStore>().init()
    }

    // 便捷属性 — 与旧 TestContext 保持相同接口
    val userService: UserService get() = koin.get()
    val authService: AuthService get() = koin.get()
    val contactService: ContactService get() = koin.get()
    val chatService: ChatService get() = koin.get()
    val messageService: MessageService get() = koin.get()
    val conversationService: ConversationService get() = koin.get()
    val deviceRepo: DeviceRepository get() = koin.get()
    val userRepo: UserRepository get() = koin.get()
    val contactRepo: ContactRepository get() = koin.get()
    val chatRepo: ChatRepository get() = koin.get()
    val conversationRepo: ConversationRepository get() = koin.get()
    val searchIndex: SearchIndex get() = koin.get()
    val healthChecker: com.virjar.tk.domain.health.HealthChecker get() = koin.get()
    val fileStore: com.virjar.tk.infra.storage.FileStore get() = koin.get()

    /** 注册用户，返回 uid */
    suspend fun registerUser(username: String = uniqueUsername("user"), password: String = "pass123"): String {
        val user = userService.register(username, password, username)
        return user.uid
    }

    override fun close() {
        koin.get<SearchIndex>().stop()
        koin.get<MessageStore>().close()
        koin.get<TokenStore>().close()
        koin.get<ClientRegistry>().stop()
        koinApp.close()
        embeddedPg.close()
        testRoot.deleteRecursively()
    }
}

/**
 * JUnit 5 Extension — 自动管理 TestEnvironment 生命周期。
 *
 * 用法：
 * ```kotlin
 * companion object {
 *     @JvmField
 *     @RegisterExtension
 *     val ext = IntegrationTestExtension()
 * }
 * private val ctx get() = ext.env
 * ```
 */
class IntegrationTestExtension : BeforeAllCallback, AfterAllCallback {
    lateinit var env: TestEnvironment

    override fun beforeAll(context: ExtensionContext) {
        env = TestEnvironment()
    }

    override fun afterAll(context: ExtensionContext) {
        if (::env.isInitialized) {
            env.close()
        }
    }
}
