package com.virjar.tk.app.ui.component.rich

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun DocumentBlockFormattingToolbar(
    controller: DocumentBlockEditorController,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val compact = maxWidth < 720.dp
        var insertMenu by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val richState = controller.activeRichState
            if (richState != null) {
                RichTextFormattingToolbar(
                    state = richState,
                    mode = RichTextToolbarMode.DOCUMENT,
                    onRequestFocus = controller::requestRichTextFocus,
                    modifier = Modifier.weight(1f).padding(4.dp),
                    testTagPrefix = "documents.editor.format",
                )
            } else {
                Text(
                    "当前为结构化内容块",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
            }

            Spacer(
                Modifier.width(1.dp).height(24.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            if (compact) {
                Box {
                    TextButton(
                        onClick = { insertMenu = true },
                        modifier = Modifier.testTag("documents.editor.block.insert"),
                    ) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("插入")
                    }
                    DropdownMenu(expanded = insertMenu, onDismissRequest = { insertMenu = false }) {
                        DocumentBlockInsertMenuItems(
                            onRich = { insertMenu = false; controller.insertParagraph() },
                            onQuote = { insertMenu = false; controller.insertQuote() },
                            onCode = { insertMenu = false; controller.insertCodeFence() },
                            onTable = { insertMenu = false; controller.insertTable() },
                        )
                    }
                }
            } else {
                DocumentBlockInsertButton(
                    label = "正文",
                    icon = { Icon(Icons.Filled.Add, null) },
                    testTag = "documents.editor.block.rich",
                    onClick = controller::insertParagraph,
                )
                DocumentBlockInsertButton(
                    label = "引用",
                    icon = { Icon(Icons.Filled.FormatQuote, null) },
                    testTag = "documents.editor.block.quote",
                    onClick = controller::insertQuote,
                )
                DocumentBlockInsertButton(
                    label = "代码块",
                    icon = { Icon(Icons.Filled.Code, null) },
                    testTag = "documents.editor.block.code",
                    onClick = controller::insertCodeFence,
                )
                DocumentBlockInsertButton(
                    label = "表格",
                    icon = { Icon(Icons.Filled.TableChart, null) },
                    testTag = "documents.editor.block.table",
                    onClick = controller::insertTable,
                )
            }
        }
    }
}

@Composable
private fun DocumentBlockInsertButton(
    label: String,
    icon: @Composable () -> Unit,
    testTag: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(testTag)) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}

@Composable
private fun DocumentBlockInsertMenuItems(
    onRich: () -> Unit,
    onQuote: () -> Unit,
    onCode: () -> Unit,
    onTable: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text("正文段落") },
        leadingIcon = { Icon(Icons.Filled.Add, null) },
        onClick = onRich,
        modifier = Modifier.testTag("documents.editor.block.rich"),
    )
    DropdownMenuItem(
        text = { Text("引用") },
        leadingIcon = { Icon(Icons.Filled.FormatQuote, null) },
        onClick = onQuote,
        modifier = Modifier.testTag("documents.editor.block.quote"),
    )
    DropdownMenuItem(
        text = { Text("代码块") },
        leadingIcon = { Icon(Icons.Filled.Code, null) },
        onClick = onCode,
        modifier = Modifier.testTag("documents.editor.block.code"),
    )
    DropdownMenuItem(
        text = { Text("表格") },
        leadingIcon = { Icon(Icons.Filled.TableChart, null) },
        onClick = onTable,
        modifier = Modifier.testTag("documents.editor.block.table"),
    )
}
