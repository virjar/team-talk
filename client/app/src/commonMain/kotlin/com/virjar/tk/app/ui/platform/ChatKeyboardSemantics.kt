package com.virjar.tk.app.ui.platform

/**
 * 聊天输入区的 Enter 语义按平台交互约定区分：
 * 桌面硬键盘为常见 IM 交互——裸 Enter 发送消息，平台命令键
 * （macOS Cmd / Windows、Linux Ctrl）+Enter 换行；
 * 移动端软键盘 Enter 保持换行，发送走输入法动作或发送按钮。
 */
expect val enterKeySendsChatMessage: Boolean
