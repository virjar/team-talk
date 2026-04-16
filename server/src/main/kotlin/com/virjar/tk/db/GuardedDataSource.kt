package com.virjar.tk.db

import com.virjar.tk.env.ThreadIOGuard
import java.io.PrintWriter
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * [DataSource] 代理，在获取连接前通过 [ThreadIOGuard] 检测受保护线程。
 *
 * 包装 [HikariDataSource][com.zaxxer.hikari.HikariDataSource]，零侵入拦截所有
 * `transaction {}` 调用——无需修改任何 DAO 或 Service 文件。
 */
class GuardedDataSource(private val delegate: DataSource) : DataSource {

    override fun getConnection(): Connection {
        ThreadIOGuard.check("PostgreSQL")
        return delegate.connection
    }

    override fun getConnection(username: String, password: String): Connection {
        ThreadIOGuard.check("PostgreSQL")
        return delegate.getConnection(username, password)
    }

    // --- 委托其余 DataSource 方法 ---

    override fun getLogWriter(): PrintWriter = delegate.logWriter

    override fun setLogWriter(out: PrintWriter) {
        delegate.logWriter = out
    }

    override fun setLoginTimeout(seconds: Int) {
        delegate.loginTimeout = seconds
    }

    override fun getLoginTimeout(): Int = delegate.loginTimeout

    override fun getParentLogger(): Logger = delegate.parentLogger

    override fun <T : Any> unwrap(iface: Class<T>): T = delegate.unwrap(iface)

    override fun isWrapperFor(iface: Class<*>): Boolean = delegate.isWrapperFor(iface)
}
