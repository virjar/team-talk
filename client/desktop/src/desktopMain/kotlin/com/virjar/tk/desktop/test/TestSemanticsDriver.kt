package com.virjar.tk.desktop.test

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.text.AnnotatedString
import java.awt.EventQueue

/** Compose semantics 查找与动作执行，独立于 HTTP 传输层。 */
internal class TestSemanticsDriver(
    private val automation: TestHttpAutomation,
) {
    /** 安全 contains（catch 内部异常，避免合并节点遍历越界）。 */
    internal fun <T> safeContains(
        cfg: SemanticsConfiguration,
        key: SemanticsPropertyKey<T>,
    ): Boolean = try {
        cfg.contains(key)
    } catch (e: Exception) {
        println("[debug] contains failed: ${e.message}")
        false
    }

    @Suppress("UNCHECKED_CAST")
    internal fun <T> safeGet(
        cfg: SemanticsConfiguration,
        key: SemanticsPropertyKey<T>,
    ): T? = try {
        if (cfg.contains(key)) cfg.get(key) else null
    } catch (e: Exception) {
        println("[debug] get failed: ${e.message}")
        null
    }

    @OptIn(ExperimentalComposeUiApi::class)
    internal fun allRoots(windowId: String = "main"): List<SemanticsNode> {
        val window = automation.windows[windowId] ?: automation.windows["main"] ?: return emptyList()
        val owners = try {
            window.semanticsOwners
        } catch (e: Exception) {
            return emptyList()
        }
        return owners.mapNotNull { it.rootSemanticsNode }
    }

    /** 可点击合并容器下的嵌套 testTag 对自动化仍然可寻址。 */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun allUnmergedRoots(windowId: String): List<SemanticsNode> {
        val window = automation.windows[windowId] ?: automation.windows["main"] ?: return emptyList()
        val owners = try {
            window.semanticsOwners
        } catch (_: Exception) {
            return emptyList()
        }
        return owners.mapNotNull { it.unmergedRootSemanticsNode }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Deprecated("Use allRoots for Popup support", ReplaceWith("allRoots(windowId)"))
    internal fun root(windowId: String = "main"): SemanticsNode? = allRoots(windowId).firstOrNull()

    /** 在语义树中查找节点：优先 testTag，其次 text。遍历所有 owners（含 Popup）。 */
    internal fun findNode(testTag: String?, text: String?, windowId: String = "main"): SemanticsNode? {
        for (root in allRoots(windowId)) {
            val found = findInTree(root, testTag, text)
            if (found != null) return found
        }
        // Desktop 上可点击的行会合并后代 semantics。保持导出的树紧凑，
        // 但回退到未合并的 owner 树，这样嵌套的稳定 testTag 不会被行自身的 tag 抹掉。
        // 动作查找仍可从该节点向上走到其可点击祖先。
        if (testTag != null) {
            for (root in allUnmergedRoots(windowId)) {
                val found = findInTree(root, testTag, text = null)
                if (found != null) return found
            }
        }
        return null
    }

    /** 从 node 开始向上找第一个带 OnClick action 的祖先（含自身）。 */
    internal fun findClickableAncestor(node: SemanticsNode): SemanticsNode? {
        var current: SemanticsNode? = node
        var depth = 0
        while (current != null && depth < 10) {
            if (safeContains(current.config, SemanticsActions.OnClick)) return current
            current = current.parent
            depth++
        }
        return null
    }

    /** 在语义树中找包含坐标 (x,y) 的最深层节点（用于坐标点击走语义 action）。 */
    internal fun findNodeAt(x: Float, y: Float, windowId: String = "main"): SemanticsNode? {
        // 遍历所有 owners（含 Popup），找到坐标命中的节点
        for (root in allRoots(windowId)) {
            val found = findNodeAtInTree(root, x, y)
            if (found != null) return found
        }
        return null
    }

    private fun findNodeAtInTree(node: SemanticsNode, x: Float, y: Float): SemanticsNode? {
        val bounds = node.boundsInWindow
        val contains = x >= bounds.left && x <= bounds.right && y >= bounds.top && y <= bounds.bottom
        if (!contains) return null
        // 优先返回最深层（子节点）的匹配
        for (child in node.children) {
            val deeper = findNodeAtInTree(child, x, y)
            if (deeper != null) return deeper
        }
        return node
    }

    private fun findInTree(node: SemanticsNode, testTag: String?, text: String?): SemanticsNode? {
        val cfg = node.config
        if (testTag != null) {
            val tag = if (cfg.contains(SemanticsProperties.TestTag)) {
                cfg[SemanticsProperties.TestTag]
            } else {
                null
            }
            if (tag == testTag) return node
        } else if (text != null) {
            val nodeText = if (cfg.contains(SemanticsProperties.Text)) {
                (cfg[SemanticsProperties.Text] as? List<*>)?.joinToString("") { it.toString() }
            } else {
                null
            }
            if (nodeText == text) return node
        }
        for (child in node.children) {
            findInTree(child, testTag, text)?.let { return it }
        }
        return null
    }

    internal fun nodeToJson(node: SemanticsNode, depth: Int = 0): String {
        if (depth > 50) return "{}"
        val cfg = node.config
        val tag: String? = if (cfg.contains(SemanticsProperties.TestTag)) {
            cfg[SemanticsProperties.TestTag]
        } else {
            null
        }
        val text: String? = if (cfg.contains(SemanticsProperties.Text)) {
            (cfg[SemanticsProperties.Text] as? List<*>)?.joinToString("") { it.toString() }
        } else {
            null
        }
        // contentDescription：图标按钮等无文字节点的定位依据（e2e 依赖）
        val contentDescription: String? = if (cfg.contains(SemanticsProperties.ContentDescription)) {
            (cfg[SemanticsProperties.ContentDescription] as? List<*>)?.joinToString("") { it.toString() }
        } else {
            null
        }
        val editableText: String? = if (cfg.contains(SemanticsProperties.EditableText)) {
            cfg[SemanticsProperties.EditableText].toString()
        } else {
            null
        }
        val progress = safeGet(cfg, SemanticsProperties.ProgressBarRangeInfo)
        val clickable = cfg.contains(SemanticsActions.OnClick)
        val bounds: Rect = node.boundsInWindow
        val children = node.children
        val json = StringBuilder("{")
        json.append("\"id\":${node.id}")
        tag?.let { json.append(",\"testTag\":\"${it.escape()}\"") }
        text?.let { json.append(",\"text\":\"${it.escape()}\"") }
        contentDescription?.let { json.append(",\"cd\":\"${it.escape()}\"") }
        editableText?.let { json.append(",\"editableText\":\"${it.escape()}\"") }
        progress?.let { info ->
            json.append(",\"progress\":{")
            json.append("\"current\":${info.current}")
            json.append(",\"min\":${info.range.start}")
            json.append(",\"max\":${info.range.endInclusive}")
            json.append(",\"steps\":${info.steps}")
            json.append("}")
        }
        json.append(",\"clickable\":$clickable")
        json.append(",\"bounds\":[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]")
        if (children.isNotEmpty()) {
            json.append(",\"children\":[")
            json.append(children.joinToString(",") { nodeToJson(it, depth + 1) })
            json.append("]")
        }
        json.append("}")
        return json.toString()
    }

    /** 调用 TextField 的 SetText 语义 action，直接设置文本。 */
    @Suppress("UNCHECKED_CAST")
    internal fun invokeSetTextAction(node: SemanticsNode, text: String): Boolean {
        // SetText action 可能在本节点或祖先（TextField 合并语义）
        val target = findNodeWithAction(node, SemanticsActions.SetText) ?: return false
        val cfg = target.config
        if (!safeContains(cfg, SemanticsActions.SetText)) return false
        val action = safeGet(cfg, SemanticsActions.SetText) ?: return false
        val lambda = action.action ?: return false
        return try {
            // SetText 是 (AnnotatedString) -> Boolean
            val annotated = AnnotatedString(text)
            runActionOnEventQueue {
                (lambda as kotlin.Function1<Any?, *>).invoke(annotated) as? Boolean ?: true
            }
        } catch (e: Exception) {
            println("[TestHttpServer] SetText action failed: ${e.message}")
            false
        }
    }

    /** 直接驱动 Slider/无障碍进度，不依赖 Robot 权限。 */
    @Suppress("UNCHECKED_CAST")
    internal fun invokeSetProgressAction(node: SemanticsNode, value: Float): Boolean {
        val target = findNodeWithAction(node, SemanticsActions.SetProgress) ?: return false
        val action = safeGet(target.config, SemanticsActions.SetProgress) ?: return false
        val lambda = action.action ?: return false
        return try {
            runActionOnEventQueue {
                (lambda as kotlin.Function1<Float, *>).invoke(value) as? Boolean ?: true
            }
        } catch (e: Exception) {
            println("[TestHttpServer] SetProgress action failed: ${e.message}")
            false
        }
    }

    /** 从 node 向上找带指定 action 的祖先（含自身）。 */
    private fun findNodeWithAction(
        node: SemanticsNode,
        action: SemanticsPropertyKey<*>,
    ): SemanticsNode? {
        var current: SemanticsNode? = node
        var depth = 0
        while (current != null && depth < 10) {
            if (safeContains(current.config, action)) return current
            current = current.parent
            depth++
        }
        return null
    }

    /** 无点击动作才允许调用方回退；业务动作抛错交 HTTP 层报告，不能伪装成聚焦成功。 */
    internal fun invokeClickAction(node: SemanticsNode): Boolean {
        val action = safeGet(node.config, SemanticsActions.OnClick)?.action ?: return false
        return runActionOnEventQueue(action)
    }

    /** 聚焦可编辑节点（TextField 通常没有 OnClick 语义）。 */
    @Suppress("UNCHECKED_CAST")
    internal fun invokeRequestFocus(node: SemanticsNode): Boolean {
        val target = findNodeWithAction(node, SemanticsActions.RequestFocus) ?: return false
        val action = safeGet(target.config, SemanticsActions.RequestFocus) ?: return false
        val lambda = action.action ?: return false
        var requested = false
        return try {
            val request = {
                requested = (lambda as kotlin.Function0<*>).invoke() as? Boolean ?: true
            }
            if (EventQueue.isDispatchThread()) request() else EventQueue.invokeAndWait(request)
            requested
        } catch (e: Exception) {
            false
        }
    }

    /** 调用节点的 OnLongClick 语义 action（用于 combinedClickable 的长按菜单）。 */
    internal fun invokeLongClickAction(node: SemanticsNode): Boolean {
        var current: SemanticsNode? = node
        // 向上查找带 OnLongClick 的节点（与 findClickableAncestor 类似）
        while (current != null) {
            val cfg = current.config
            if (safeContains(cfg, SemanticsActions.OnLongClick)) {
                val action = safeGet(cfg, SemanticsActions.OnLongClick) ?: return false
                val lambda = action.action ?: return false
                return try {
                    runActionOnEventQueue {
                        (lambda as kotlin.Function0<*>).invoke() as? Boolean ?: true
                    }
                } catch (e: Exception) {
                    false
                }
            }
            current = current.parent
        }
        return false
    }

    /** Compose 语义动作会更新 UI/焦点，必须在 AWT 事件线程执行。 */
    private fun runActionOnEventQueue(action: () -> Boolean): Boolean {
        if (EventQueue.isDispatchThread()) return action()
        var result = false
        EventQueue.invokeAndWait { result = action() }
        return result
    }
}
