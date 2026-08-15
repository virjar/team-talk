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

    // 真实 PG（本地 teamtalk 库，与 local profile 同环境；init 时 TRUNCATE 清场）
    private val pgUser = System.getenv("TK_E2E_PG_USER") ?: System.getProperty("user.name")
    private val pgJdbc = "jdbc:postgresql://localhost:5432/teamtalk?user=$pgUser"

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
        java.sql.DriverManager.getConnection(pgJdbc).use { conn ->
            val tables = conn.createStatement().executeQuery(
                "SELECT tablename FROM pg_tables WHERE schemaname='public'").let { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
            if (tables.isNotEmpty()) {
                conn.createStatement().execute("TRUNCATE ${tables.joinToString(", ")} RESTART IDENTITY CASCADE")
            }
        }
        DatabaseFactory.create(jdbcUrl = pgJdbc, user = pgUser, password = "", maxPoolSize = 4)
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
