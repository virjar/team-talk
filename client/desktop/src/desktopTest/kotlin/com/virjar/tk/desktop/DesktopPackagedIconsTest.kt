package com.virjar.tk.desktop

import java.awt.image.BufferedImage
import java.net.URL
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class DesktopPackagedIconsTest {
    @Test
    fun `packaged icons load when the AWT thread cannot see the application payload`() {
        val owner = DesktopIconResources::class.java
        val resource = assertNotNull(owner.getResource("/icon/icon-16.png"))
        val classpath = arrayOf(
            owner.protectionDomain.codeSource.location,
            Unit::class.java.protectionDomain.codeSource.location,
            URL(resource.toExternalForm().removeSuffix("icon/icon-16.png")),
        )
        val thread = Thread.currentThread()
        val original = thread.contextClassLoader
        URLClassLoader(classpath, ClassLoader.getPlatformClassLoader()).use { payload ->
            val packagedOwner = payload.loadClass(owner.name)
            assertNotSame(owner, packagedOwner)
            val instance = packagedOwner.getField("INSTANCE").get(null)
            val load = packagedOwner.getMethod("load", Int::class.javaPrimitiveType)
            try {
                // 新壳加载应用，但 EDT 可以早于应用建立，仍持有不可见负载的 loader。
                for (context in listOf(null, ClassLoader.getPlatformClassLoader())) {
                    thread.contextClassLoader = context
                    assertNull(context?.getResource("icon/icon-16.png"))
                    for (size in listOf(16, 32, 48, 64, 128, 256, 512)) {
                        val image = assertNotNull(load.invoke(instance, size) as BufferedImage?)
                        assertEquals(size, image.width)
                        assertEquals(size, image.height)
                    }
                }
            } finally {
                thread.contextClassLoader = original
            }
        }
    }
}
