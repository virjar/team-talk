package com.virjar.tk.desktop

import com.virjar.tk.app.identity.ClientIdentity
import java.awt.BorderLayout
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.Instant
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.UIManager

/** 数据目录可用之前不能初始化 AppLog；诊断只写 stderr，用户可主动复制，不另建诊断文件。 */
internal fun showDesktopStartupFailure(stage: String, failure: Throwable) {
    val report = startupFailureReport(stage, failure)
    runCatching {
        System.err.write(report.encodeToByteArray())
        System.err.flush()
    }
    if (GraphicsEnvironment.isHeadless()) return
    runCatching {
        if (SwingUtilities.isEventDispatchThread()) {
            showFailureDialog(report)
        } else {
            SwingUtilities.invokeAndWait { showFailureDialog(report) }
        }
    }
    // Swing 或剪贴板不可用时，原始启动错误仍已交给 stderr，不尝试在未知位置落盘。
}

private fun startupFailureReport(stage: String, failure: Throwable): String {
    val report = buildString {
        appendLine("${ClientIdentity.DISPLAY_NAME} 启动失败")
        appendLine("阶段：$stage")
        appendLine("时间：${Instant.now()}")
        appendLine("构建：${BuildConfig.BUILD_IDENTITY}")
        appendLine("构建时间：${BuildConfig.BUILD_TIME}")
        appendLine("系统：${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
        appendLine("Java：${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
        appendLine()
        // 保留真正的异常链和目录错误；不附加环境变量、命令行、用户名或用户目录清单。
        append(failure.stackTraceToString())
    }
    val limit = 64 * 1024
    return if (report.length <= limit) report else report.take(limit) + "\n（详情过长，后续内容已截断）\n"
}

private fun showFailureDialog(report: String) {
    val explanation = JTextArea(
        "工作区尚未打开。请复制详情交给部署维护者，检查目录权限或数据版本。\n请保留已有资料，不要直接删除数据目录。",
    ).apply {
        isEditable = false
        isOpaque = false
        font = UIManager.getFont("Label.font")
        lineWrap = true
        wrapStyleWord = true
    }
    val details = JTextArea(report, 16, 68).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        caretPosition = 0
    }
    val copyStatus = JLabel("详情可能包含本机路径；请仅提供给可信的维护者。")
    val content = JPanel(BorderLayout(0, 10)).apply {
        add(explanation, BorderLayout.NORTH)
        add(JScrollPane(details), BorderLayout.CENTER)
        add(copyStatus, BorderLayout.SOUTH)
    }
    val copy = JButton("复制全部详情")
    val close = JButton("关闭")
    val pane = JOptionPane(
        content,
        JOptionPane.ERROR_MESSAGE,
        JOptionPane.DEFAULT_OPTION,
        null,
        arrayOf(copy, close),
        close,
    )
    val dialog = pane.createDialog(null, "${ClientIdentity.DISPLAY_NAME} 启动失败")
    copy.addActionListener {
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(report), null)
            copyStatus.text = "已复制全部详情，可关闭窗口后发送给维护者。"
        } catch (_: IllegalStateException) {
            copyStatus.text = "剪贴板暂不可用，请重试，或选中上方详情后手动复制。"
        } catch (_: SecurityException) {
            copyStatus.text = "无法访问剪贴板，请选中上方详情后手动复制。"
        }
    }
    close.addActionListener { dialog.dispose() }
    dialog.rootPane.defaultButton = close
    dialog.rootPane.registerKeyboardAction(
        { dialog.dispose() },
        KeyStroke.getKeyStroke("ESCAPE"),
        JComponent.WHEN_IN_FOCUSED_WINDOW,
    )
    try {
        dialog.isVisible = true
    } finally {
        dialog.dispose()
    }
}
