package com.virjar.tk.server.domain.document


/** Document 域的类型化异常集合；每个异常的语义见各 KDoc。 */


/** 已存在的文档空间实时 ACL 拒绝操作者时的稳定 RPC 403 边界。 */
class DocumentAccessDeniedException(message: String) : RuntimeException(message)


/** 精确文档领域资源缺失或非活跃时的稳定 RPC 404 边界。 */
class DocumentNotFoundException(message: String) : RuntimeException(message)


/**
 * 文档修订的乐观锁冲突。
 *
 * 这个领域信号被刻意设计为没有传输依赖。RPC 把它映射到状态码 409，
 * 而普通校验错误仍为状态码 400。
 */
class DocumentRevisionConflictException(
    message: String = MESSAGE,
) : RuntimeException(message) {
    companion object {
        const val MESSAGE = "文档已被其他成员修改，请基于最新版本重试"
    }
}


/** 过期资产归属交接计划的乐观锁冲突。 */
class DocumentCustodyConflictException(
    message: String = MESSAGE,
) : RuntimeException(message) {
    companion object {
        const val MESSAGE = "文档空间归属已发生变化，请基于最新状态重试"
    }
}


/** 客户端捕获其文档树之后，移动目标已变更或消失。 */
class DocumentHierarchyConflictException(
    message: String = MESSAGE,
) : RuntimeException(message) {
    companion object {
        const val MESSAGE = "目标父文档已发生变化，请刷新目录后重试"
    }
}
