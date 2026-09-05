package com.virjar.tk.server.infra.db

import com.virjar.tk.server.env.ThreadIOGuard
import java.sql.Connection
import javax.sql.DataSource

/**
 * 针对一个服务器自有连接池的完整 PostgreSQL 阻塞 IO 守卫。
 *
 * Exposed 通过其配置的 [DataSource] 为每个外层事务获取 JDBC 连接。
 * 包裹这唯一的拥有权边界，即可覆盖同步 `transaction`、
 * `newSuspendedTransaction`、UoW 范围内的仓库调用、健康/管理查询以及未来的直接
 * 仓库访问，而无需依赖每个方法作者记住守卫调用。
 */
internal class BlockingIoGuardDataSource(
    private val delegate: DataSource,
) : DataSource by delegate {
    override fun getConnection(): Connection {
        ThreadIOGuard.check(POSTGRES_CONNECTION_OPERATION)
        return delegate.connection
    }

    override fun getConnection(username: String?, password: String?): Connection {
        ThreadIOGuard.check(POSTGRES_CONNECTION_OPERATION)
        return delegate.getConnection(username, password)
    }

    private companion object {
        const val POSTGRES_CONNECTION_OPERATION = "PostgreSQL connection acquisition"
    }
}
