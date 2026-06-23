package com.virjar.tk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.WindowScope
import java.awt.Image
import java.awt.Toolkit
import java.net.URL

/**
 * Desktop 窗口图标加载。
 *
 * 从 classpath:/icon/ 下加载多尺寸 PNG，组装成 AWT 多分辨率 Image，
 * 交给 [java.awt.Window.setIconImages]，让系统按场景（任务栏/标题栏/Alt-Tab）选最佳尺寸。
 */
private fun loadIconImages(): List<Image> {
    val toolkit = Toolkit.getDefaultToolkit()
    return listOf(16, 32, 48, 64, 128, 256, 512).mapNotNull { size ->
        val url: URL? = Thread.currentThread().contextClassLoader?.getResource("icon/icon-$size.png")
        url?.let { toolkit.getImage(it) }
    }
}

/**
 * 在 [WindowScope] 内为本窗口设置图标。
 * 在 Composable 进入组合后调用 [getWindow] 取底层 AWT 窗口，注入图标。
 */
@Composable
fun WindowScope.setTeamTalkIcon() {
    LaunchedEffect(Unit) {
        val images = loadIconImages()
        if (images.isNotEmpty()) window.setIconImages(images)
    }
}
