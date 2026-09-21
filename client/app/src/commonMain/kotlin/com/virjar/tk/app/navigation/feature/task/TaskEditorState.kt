package com.virjar.tk.app.navigation.feature.task

import com.virjar.tk.shared.platform.platformRandomUuid

import com.virjar.tk.protocol.model.TaskDraft
import com.virjar.tk.protocol.model.TaskDetails
import com.virjar.tk.protocol.model.TaskOptions
import com.virjar.tk.protocol.model.TaskPolicy
import com.virjar.tk.protocol.model.TaskRecurrenceRule
import com.virjar.tk.protocol.model.WorkTask
import kotlinx.datetime.*
import com.virjar.tk.app.ui.platform.localUiDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
internal data class TaskEditorState(
    val original: WorkTask?,
    val title: String,
    val description: String,
    val assigneeUid: String,
    val contextKind: Int = TaskPolicy.CONTEXT_NONE,
    val contextId: String = "",
    val deadline: TaskDeadlineInput = TaskDeadlineInput(),
    val editorKey: String = platformRandomUuid(),
    @Transient val uploading: Boolean = false,
    val originalDetails: TaskDetails? = null,
    val options: TaskOptions = TaskOptions(),
    val start: TaskDeadlineInput = TaskDeadlineInput(),
    val recurrenceInput: TaskRecurrenceInput = TaskRecurrenceInput(),
) {
    fun draft(): TaskDraft {
        require(title.trim().isNotEmpty()) { "请填写任务标题" }
        require(title.trim().length <= TaskPolicy.MAX_TITLE_LENGTH) { "任务标题最多 ${TaskPolicy.MAX_TITLE_LENGTH} 字" }
        require(description.length <= TaskPolicy.MAX_DESCRIPTION_LENGTH) { "任务描述最多 ${TaskPolicy.MAX_DESCRIPTION_LENGTH} 字" }
        require(assigneeUid.isNotBlank()) { "请选择执行人" }
        require(contextKind == TaskPolicy.CONTEXT_NONE || contextId.isNotBlank()) { "请选择关联的群或部门" }
        val dueAt = when {
            recurrenceRule() != null -> null
            original != null && deadline == TaskDeadlineInput.from(original.dueAt) -> original.dueAt
            else -> deadline.epochMillis()
        }
        return TaskDraft(title.trim(), description, assigneeUid, contextKind, contextId, dueAt)
    }

    fun optionsForSave(): TaskOptions {
        val originalStart = originalDetails?.options?.startsAt
        val startsAt = when {
            recurrenceRule() != null -> null
            originalDetails != null && start == TaskDeadlineInput.from(originalStart) -> originalStart
            else -> start.epochMillis()
        }
        val dueAt = draft().dueAt
        require(startsAt == null || dueAt == null || startsAt <= dueAt) { "开始时间不能晚于截止时间" }
        return options.copy(startsAt = startsAt,
            shareToGroup = contextKind == TaskPolicy.CONTEXT_GROUP && options.shareToGroup)
    }

    /** 编辑的是已生成的单期，不把表单保存隐式扩散到未来期次。 */
    fun recurrenceRule(): TaskRecurrenceRule? = if (original != null || !recurrenceInput.enabled) null else recurrenceInput.rule()

    companion object {
        fun from(task: WorkTask) = TaskEditorState(task, task.title, task.description, task.assigneeUid,
            task.contextKind, task.contextId, TaskDeadlineInput.from(task.dueAt))
        fun from(details: TaskDetails) = from(details.task).copy(
            originalDetails = details,
            options = details.options,
            start = TaskDeadlineInput.from(details.options.startsAt),
        )
        fun create(myUid: String) = TaskEditorState(null, "", "", myUid,
            options = TaskOptions(descriptionFormat = TaskOptions.MARKDOWN))
    }
}

/** 周与月都使用首次日期作为日历锚点；原始输入留给表单，服务器计算每期时间。 */
@Serializable
internal data class TaskRecurrenceInput(
    val enabled: Boolean = false,
    val frequency: Int = TaskRecurrenceRule.WEEKLY,
    val interval: String = "1",
    val firstDate: String = localUiDateTime().date.toString(),
    val startLocalTime: String = "09:00",
    val dueLocalTime: String = "18:00",
    val timeZone: String = TimeZone.currentSystemDefault().id,
) {
    fun rule(): TaskRecurrenceRule {
        require(frequency in setOf(TaskRecurrenceRule.WEEKLY, TaskRecurrenceRule.MONTHLY)) { "请选择按周或按月重复" }
        val step = interval.toIntOrNull()
        require(step != null && step in 1..12) { "重复间隔请填写 1 到 12" }
        val date = try { LocalDate.parse(firstDate) } catch (_: Exception) {
            throw IllegalArgumentException("请填写有效的首次日期，例如 2026-09-14")
        }
        require(date.year in 1..9999) { "首次日期无效" }
        val time = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
        require(startLocalTime.matches(time) && dueLocalTime.matches(time)) { "请填写开始和截止时间，例如 09:00、18:00" }
        require(dueLocalTime > startLocalTime) { "每期截止时间必须晚于当天开始时间" }
        val zone = try { TimeZone.of(timeZone.trim()) } catch (_: Exception) {
            throw IllegalArgumentException("时区无效，例如 Asia/Shanghai")
        }
        return TaskRecurrenceRule(frequency, step, date.toString(), startLocalTime, dueLocalTime, zone.id)
    }
}

internal fun taskRecurrenceLabel(rule: TaskRecurrenceRule): String {
    val date = LocalDate.parse(rule.firstDate)
    return if (rule.frequency == TaskRecurrenceRule.WEEKLY) {
        val day = listOf("一", "二", "三", "四", "五", "六", "日")[date.dayOfWeek.isoDayNumber - 1]
        if (rule.interval == 1) "每周$day" else "每 ${rule.interval} 周的周$day"
    } else if (rule.interval == 1) "每月 ${date.day} 日" else "每 ${rule.interval} 个月的 ${date.day} 日"
}

/** Android SavedState 只保存一张表单，不拥有第二个编辑器或待发送命令。 */
@Serializable
internal data class SavedTaskEditor(
    val deploymentFingerprint: String,
    val datasetId: String,
    val ownerUid: String,
    val editor: TaskEditorState,
    val version: Int = 1,
)

internal fun taskStatusLabel(status: Int): String = when (status) {
    TaskPolicy.TODO -> "待处理"
    TaskPolicy.IN_PROGRESS -> "进行中"
    TaskPolicy.DONE -> "已完成"
    TaskPolicy.CANCELLED -> "已取消"
    else -> "未知状态"
}

internal fun taskIsOverdue(task: WorkTask, now: Long): Boolean =
    task.status in setOf(TaskPolicy.TODO, TaskPolicy.IN_PROGRESS) && task.dueAt?.let { it < now } == true

internal data class TaskContextOption(val kind: Int, val id: String, val name: String)
internal data class TaskShareOption(val chatId: String, val name: String)
