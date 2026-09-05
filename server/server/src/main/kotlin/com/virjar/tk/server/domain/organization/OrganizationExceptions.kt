package com.virjar.tk.server.domain.organization


/** Organization 域的类型化异常集合。 */


/** 移除该成员关系会使组织节点的负责人引用不一致。 */
class OrganizationMemberRemovalConflictException(message: String) : RuntimeException(message)


/** 受管组织节点在仍拥有活跃资产时不能被归档。 */
class OrganizationUnitArchiveConflictException(message: String) : RuntimeException(message)
