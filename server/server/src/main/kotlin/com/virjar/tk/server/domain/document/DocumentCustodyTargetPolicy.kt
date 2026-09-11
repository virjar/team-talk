package com.virjar.tk.server.domain.document

import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.UserRole
import com.virjar.tk.protocol.model.UserStatus

/**
 * 空间归属交接目标的共享不变量：单条用户路径与批量管理路径必须经过同一份校验，
 * 只有事实来源（实时仓库查询 vs 预载快照）不同。
 */
internal object DocumentCustodyTargetPolicy {
    fun requireValidOwner(ownerPrincipalType: Int, ownerPrincipalId: String, stewardUid: String) {
        require(
            ownerPrincipalType == DocumentSpaceGrant.PRINCIPAL_USER ||
                ownerPrincipalType == DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
        ) { "目标归属主体类型非法" }
        if (ownerPrincipalType == DocumentSpaceGrant.PRINCIPAL_USER) {
            require(ownerPrincipalId == stewardUid) { "个人持有空间必须由本人负责" }
        }
    }

    fun requireActiveSteward(role: Int, status: Int) {
        require(role == UserRole.HUMAN && status == UserStatus.ACTIVE) {
            "目标责任人必须是活动普通用户"
        }
    }

    fun requireOwnerUnitActive(unitActive: Boolean) {
        require(unitActive) { "目标归属组织节点不存在或已归档" }
    }
}
