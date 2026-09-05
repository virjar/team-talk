package com.virjar.tk.server.infra.db

import com.virjar.tk.server.env.BlockingIoOnProtectedThreadException
import com.virjar.tk.server.env.ThreadIOGuard
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLFeatureNotSupportedException
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BlockingIoGuardDataSourceTest {
    @Test
    fun `both connection entry points fail before touching the pool on a protected thread`() {
        val delegate = RecordingDataSource()
        val guarded = BlockingIoGuardDataSource(delegate)

        ThreadIOGuard.protectCurrentThread()
        try {
            assertFailsWith<BlockingIoOnProtectedThreadException> { guarded.connection }
            assertFailsWith<BlockingIoOnProtectedThreadException> {
                guarded.getConnection("database-user", "database-password")
            }
            assertEquals(0, delegate.connectionAttempts.get())
        } finally {
            ThreadIOGuard.unprotectCurrentThread()
        }

        assertFailsWith<DelegateConnectionAttempt> { guarded.connection }
        assertFailsWith<DelegateConnectionAttempt> {
            guarded.getConnection("database-user", "database-password")
        }
        assertEquals(2, delegate.connectionAttempts.get())
    }

    private class RecordingDataSource : DataSource {
        val connectionAttempts = AtomicInteger(0)

        override fun getConnection(): Connection {
            connectionAttempts.incrementAndGet()
            throw DelegateConnectionAttempt()
        }

        override fun getConnection(username: String?, password: String?): Connection {
            connectionAttempts.incrementAndGet()
            throw DelegateConnectionAttempt()
        }

        override fun getLogWriter(): PrintWriter? = null

        override fun setLogWriter(out: PrintWriter?) = Unit

        override fun setLoginTimeout(seconds: Int) = Unit

        override fun getLoginTimeout(): Int = 0

        override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException()

        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLFeatureNotSupportedException()

        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }

    private class DelegateConnectionAttempt : RuntimeException()
}
