package com.virjar.tk.server.domain.organization

import com.virjar.tk.protocol.model.OrganizationCapacityPolicy
import java.util.ArrayDeque

/** 用于校验与遍历活跃组织树的最小不可变视图。 */
internal data class OrganizationHierarchyNode(
    val unitId: String,
    val parentId: String?,
)

/**
 * 一个经过校验、容量受限的组织层级。
 *
 * 根深度为 1。校验是迭代的，复杂度 O(U)：每条父边只解析一次，其深度被记忆化。
 * 遍历同样是迭代的，因此损坏或病态形状的数据无法耗尽 JVM 调用栈。
 */
internal class OrganizationHierarchy private constructor(
    private val nodes: Map<String, OrganizationHierarchyNode>,
    private val children: Map<String, List<String>>,
    private val depths: Map<String, Int>,
) {
    fun depth(unitId: String): Int = depths[unitId]
        ?: throw IllegalArgumentException("组织节点不存在: $unitId")

    fun ancestors(unitId: String): Set<String> {
        require(nodes.containsKey(unitId)) { "组织节点不存在: $unitId" }
        val result = linkedSetOf<String>()
        var cursor: String? = unitId
        while (cursor != null) {
            check(result.size < OrganizationCapacityPolicy.MAX_TREE_DEPTH) {
                OrganizationCapacityPolicy.TREE_DEPTH_REASON
            }
            check(result.add(cursor)) { "组织架构存在循环: $cursor" }
            cursor = nodes.getValue(cursor).parentId
        }
        return result
    }

    fun descendants(unitId: String): Set<String> {
        require(nodes.containsKey(unitId)) { "组织节点不存在: $unitId" }
        val result = linkedSetOf<String>()
        val pending = ArrayDeque<Traversal>()
        pending.addLast(Traversal(unitId, relativeDepth = 1))
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            check(current.relativeDepth <= OrganizationCapacityPolicy.MAX_TREE_DEPTH) {
                OrganizationCapacityPolicy.TREE_DEPTH_REASON
            }
            check(result.add(current.unitId)) { "组织架构存在循环: ${current.unitId}" }
            check(result.size <= OrganizationCapacityPolicy.MAX_ACTIVE_UNITS) {
                OrganizationCapacityPolicy.UNIT_CAPACITY_REASON
            }
            children[current.unitId].orEmpty().asReversed().forEach { childId ->
                pending.addLast(Traversal(childId, current.relativeDepth + 1))
            }
        }
        return result
    }

    private data class Traversal(val unitId: String, val relativeDepth: Int)

    companion object {
        fun validate(source: Collection<OrganizationHierarchyNode>): OrganizationHierarchy {
            check(source.size <= OrganizationCapacityPolicy.MAX_ACTIVE_UNITS) {
                OrganizationCapacityPolicy.UNIT_CAPACITY_REASON
            }
            val nodes = linkedMapOf<String, OrganizationHierarchyNode>()
            source.forEach { node ->
                check(nodes.put(node.unitId, node) == null) {
                    "组织架构包含重复节点: ${node.unitId}"
                }
            }
            if (nodes.isEmpty()) {
                return OrganizationHierarchy(emptyMap(), emptyMap(), emptyMap())
            }

            val roots = nodes.values.filter { it.parentId == null }
            check(roots.size == 1) { "非空组织架构必须恰好包含一个根节点" }
            nodes.values.forEach { node ->
                val parentId = node.parentId ?: return@forEach
                check(parentId != node.unitId) { "组织架构存在循环: ${node.unitId}" }
                check(nodes.containsKey(parentId)) {
                    "组织节点 ${node.unitId} 引用了不存在的父节点: $parentId"
                }
            }

            val depths = hashMapOf<String, Int>()
            nodes.keys.forEach { start ->
                if (depths.containsKey(start)) return@forEach
                val chain = mutableListOf<String>()
                val unresolved = hashSetOf<String>()
                var cursor: String? = start
                while (cursor != null && !depths.containsKey(cursor)) {
                    check(unresolved.add(cursor)) { "组织架构存在循环: $cursor" }
                    chain += cursor
                    cursor = nodes.getValue(cursor).parentId
                }
                var depth = cursor?.let { depths.getValue(it) } ?: 0
                chain.asReversed().forEach { unitId ->
                    depth += 1
                    require(depth <= OrganizationCapacityPolicy.MAX_TREE_DEPTH) {
                        OrganizationCapacityPolicy.TREE_DEPTH_REASON
                    }
                    depths[unitId] = depth
                }
            }

            val children = nodes.values
                .filter { it.parentId != null }
                .groupBy({ it.parentId!! }, { it.unitId })
                .mapValues { (_, childIds) -> childIds.sorted() }
            return OrganizationHierarchy(nodes.toMap(), children, depths.toMap())
        }
    }
}
