package com.virjar.tk.server.infra.db

import org.intellij.lang.annotations.Language
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.statements.StatementType
import java.sql.ResultSet

/**
 * 执行参数化 SQL，而不把表拥有的 [IColumnType] 借给 Exposed。
 *
 * Exposed 0.61 会把传入 `Transaction.exec` 的每个列类型就地标记为可空。表的
 * 列类型是进程级 schema 元数据，因此直接传入会静默改变之后的
 * 校验与 schema 检查。下面每个参数改为自持可空标志，
 * 同时把数据库转换与 JDBC 绑定行为委托给真正的列类型。
 */
internal fun Transaction.execRawSql(
    @Language("sql") stmt: String,
    args: Iterable<Pair<IColumnType<*>, Any?>>,
    explicitStatementType: StatementType? = null,
) = exec(
    stmt = stmt,
    args = args.withIsolatedNullability(),
    explicitStatementType = explicitStatementType,
)

/** 原始 SQL 参数在到达 Exposed 之前必须被隔离的原因参见 [execRawSql]。 */
internal fun <T : Any> Transaction.execRawSql(
    @Language("sql") stmt: String,
    args: Iterable<Pair<IColumnType<*>, Any?>>,
    explicitStatementType: StatementType? = null,
    transform: (ResultSet) -> T?,
): T? = exec(
    stmt = stmt,
    args = args.withIsolatedNullability(),
    explicitStatementType = explicitStatementType,
    transform = transform,
)

private fun Iterable<Pair<IColumnType<*>, Any?>>.withIsolatedNullability(): List<Pair<IColumnType<*>, Any?>> =
    map { (columnType, value) -> columnType.withIsolatedNullability() to value }

@Suppress("UNCHECKED_CAST")
private fun IColumnType<*>.withIsolatedNullability(): IColumnType<*> =
    IsolatedRawSqlColumnType(this as IColumnType<Any?>)

private class IsolatedRawSqlColumnType<T>(
    private val delegate: IColumnType<T>,
) : IColumnType<T> by delegate {
    override var nullable: Boolean = delegate.nullable

    // Kotlin 委托会在 delegate 上评估对可空性敏感的格式化。同样保持在本地，
    // 这样在 Exposed 把这个一次性包装标记为可空之后，
    // SQL 诊断也能安全地渲染 null 参数。
    override fun valueToString(value: T?): String = when (value) {
        null -> nullLiteral()
        else -> delegate.valueToString(value)
    }

    override fun valueAsDefaultString(value: T?): String = when (value) {
        null -> nullLiteral()
        else -> delegate.valueAsDefaultString(value)
    }

    override fun toString(): String = delegate.toString()

    private fun nullLiteral(): String {
        check(nullable) { "NULL in non-nullable raw SQL parameter" }
        return "NULL"
    }
}
