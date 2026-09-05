package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.component.rich.PendingAssetJobState
import com.virjar.tk.app.ui.theme.Tk

/** 有界的恢复面板：大量缓慢/失败的上传绝不能把编辑器挤出屏幕。 */
@Composable
internal fun PendingAssetRows(
    jobs: List<PendingAssetJob>,
    testTagPrefix: String,
    onRetry: (PendingAssetJob) -> Unit,
    onDiscard: (PendingAssetJob) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleJobs = jobs.filter {
        it.state != PendingAssetJobState.READY && it.state != PendingAssetJobState.CANCELLED
    }
    if (visibleJobs.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth()
            .heightIn(max = 184.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        visibleJobs.forEach { job ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .testTag("$testTagPrefix.asset.pending.${job.assetId}"),
            ) {
                Column(Modifier.padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.sm)) {
                    val failed = job.state == PendingAssetJobState.FAILED
                    val actions = pendingAssetRowActions(job)
                    if (failed) {
                        Text(
                            pendingAssetLabel(job),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            actions.forEach { action ->
                                TextButton(
                                    onClick = {
                                        performPendingAssetRowAction(action, job, onRetry, onDiscard)
                                    },
                                    modifier = Modifier.testTag(
                                        "$testTagPrefix.asset.${action.testTagSegment}.${job.assetId}",
                                    ),
                                ) {
                                    Text(action.label)
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                pendingAssetLabel(job),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val action = actions.single()
                            TextButton(
                                onClick = {
                                    performPendingAssetRowAction(action, job, onRetry, onDiscard)
                                },
                                modifier = Modifier.testTag(
                                    "$testTagPrefix.asset.${action.testTagSegment}.${job.assetId}",
                                ),
                            ) {
                                Text(action.label)
                            }
                        }
                    }
                    if (job.state == PendingAssetJobState.UPLOADING) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { job.progress },
                            modifier = Modifier.fillMaxWidth()
                                .testTag("$testTagPrefix.asset.progress.${job.assetId}"),
                        )
                    }
                }
            }
        }
    }
}

internal enum class PendingAssetRowAction(
    val testTagSegment: String,
    val label: String,
) {
    RETRY("retry", "重试"),
    CANCEL("cancel", "取消上传"),
    REMOVE("remove", "移除"),
}

internal fun pendingAssetRowActions(job: PendingAssetJob): List<PendingAssetRowAction> =
    if (job.state == PendingAssetJobState.FAILED) {
        listOf(PendingAssetRowAction.RETRY, PendingAssetRowAction.REMOVE)
    } else {
        listOf(PendingAssetRowAction.CANCEL)
    }

internal fun performPendingAssetRowAction(
    action: PendingAssetRowAction,
    job: PendingAssetJob,
    onRetry: (PendingAssetJob) -> Unit,
    onDiscard: (PendingAssetJob) -> Unit,
) {
    when (action) {
        PendingAssetRowAction.RETRY -> onRetry(job)
        PendingAssetRowAction.CANCEL,
        PendingAssetRowAction.REMOVE,
        -> onDiscard(job)
    }
}

private fun pendingAssetLabel(job: PendingAssetJob): String = when (job.state) {
    PendingAssetJobState.LOCAL -> "附件等待处理"
    PendingAssetJobState.PREPARING -> "正在准备附件"
    PendingAssetJobState.UPLOADING -> "正在上传附件"
    PendingAssetJobState.FAILED -> "附件上传失败：${job.failureReason.orEmpty()}"
    PendingAssetJobState.READY -> "附件已就绪"
    PendingAssetJobState.CANCELLED -> "附件已取消"
}
