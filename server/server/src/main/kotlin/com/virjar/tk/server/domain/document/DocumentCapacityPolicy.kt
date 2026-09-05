package com.virjar.tk.server.domain.document

/**
 * 文档聚合的硬性基数预算。
 *
 * 这些是活跃行配额：归档空间或删除文档会释放其槽位。PostgreSQL 适配器在持有串行化该
 * 聚合的行锁（空间创建对应所有者 User 行，树写入对应 DocumentSpaces 行）时评估每个写入
 * 预算。读取投影只取一行溢出探针，并且默认拒绝（fail closed），而不是悄悄截断一个本来
 * 就不可能通过仓储持久化出来的层级。
 */
internal object DocumentCapacityPolicy {
    const val MAX_ACTIVE_SPACES_PER_OWNER = 128
    /** 人类责任人是每个活跃空间唯一的隐式所有者能力主体。 */
    const val MAX_ACTIVE_STEWARDSHIPS_PER_USER = 128
    /** 直接用户授权按主体全局计数；组织授权使用自己的空间上限。 */
    const val MAX_DIRECT_DOCUMENT_GRANTS_PER_USER = 1_000
    /** ACL 变更的活跃 ACK 丢失身份；空操作命令也消耗相同的有限预算。 */
    const val MAX_POLICY_MUTATION_RECEIPTS_PER_ACTOR = 1_024
    /** 文档移动/重命名命令的活跃 ACK 丢失身份。 */
    const val MAX_NODE_MOVE_RECEIPTS_PER_ACTOR = 1_024
    const val MAX_ACTIVE_DOCUMENTS_PER_SPACE = 10_000
    const val MAX_ACTIVE_CHILDREN_PER_PARENT = 512
    /**
     * 首页 RPC 最多暴露 50 行，但若只保留一页，撤回一个空间就会把其他仍可访问空间的有用
     * 最近记录全部抹掉。二十页保留了一个实用的个人工作集，同时防止这个辅助索引随用户
     * 历史上打开过的文档总数一起增长。
     */
    const val MAX_RECENT_DOCUMENTS_PER_USER = 1_000
    const val ACTIVE_SPACE_OVERFLOW_PROBE_LIMIT = MAX_ACTIVE_SPACES_PER_OWNER + 1
    const val ACTIVE_STEWARDSHIP_OVERFLOW_PROBE_LIMIT = MAX_ACTIVE_STEWARDSHIPS_PER_USER + 1
    const val DIRECT_USER_GRANT_OVERFLOW_PROBE_LIMIT = MAX_DIRECT_DOCUMENT_GRANTS_PER_USER + 1
    const val ACTIVE_CHILD_OVERFLOW_PROBE_LIMIT = MAX_ACTIVE_CHILDREN_PER_PARENT + 1

    fun requireSpaceSlot(activeSpaceCount: Long) {
        require(activeSpaceCount < MAX_ACTIVE_SPACES_PER_OWNER) {
            "每位所有者最多只能拥有 $MAX_ACTIVE_SPACES_PER_OWNER 个活动文档空间"
        }
    }

    fun requireStewardshipSlot(activeStewardshipCount: Long) {
        require(activeStewardshipCount < MAX_ACTIVE_STEWARDSHIPS_PER_USER) {
            "每位责任人最多只能负责 $MAX_ACTIVE_STEWARDSHIPS_PER_USER 个活动文档空间"
        }
    }

    fun requireDirectUserGrantSlot(currentGrantCount: Long) {
        require(currentGrantCount < MAX_DIRECT_DOCUMENT_GRANTS_PER_USER) {
            "每位用户最多只能拥有 $MAX_DIRECT_DOCUMENT_GRANTS_PER_USER 条直接文档空间授权"
        }
    }

    fun requireDocumentSlot(activeDocumentCount: Long) {
        require(activeDocumentCount < MAX_ACTIVE_DOCUMENTS_PER_SPACE) {
            "每个文档空间最多只能包含 $MAX_ACTIVE_DOCUMENTS_PER_SPACE 篇活动文档"
        }
    }

    fun requireChildSlot(activeChildCount: Long) {
        require(activeChildCount < MAX_ACTIVE_CHILDREN_PER_PARENT) {
            "同一层级最多只能包含 $MAX_ACTIVE_CHILDREN_PER_PARENT 篇活动文档"
        }
    }

    fun requireOwnedSpaceProjection(actualCount: Int) {
        require(actualCount <= MAX_ACTIVE_SPACES_PER_OWNER) {
            "所有者活动文档空间数量超过容量上限 $MAX_ACTIVE_SPACES_PER_OWNER"
        }
    }

    fun requireChildProjection(actualCount: Int) {
        require(actualCount <= MAX_ACTIVE_CHILDREN_PER_PARENT) {
            "同一层级活动文档数量超过容量上限 $MAX_ACTIVE_CHILDREN_PER_PARENT"
        }
    }
}
