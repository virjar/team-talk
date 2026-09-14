package com.virjar.tk.desktop

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** 图标属于应用负载，不能依赖 AWT/协程线程继承的启动壳 contextClassLoader。 */
internal object DesktopIconResources {
    fun load(size: Int): BufferedImage? =
        DesktopIconResources::class.java.getResourceAsStream("/icon/icon-$size.png")?.use(ImageIO::read)
}
