package com.virjar.tk.desktop

import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopImageClipboardTest {
    @Test
    fun `written image is readable back from the system clipboard`() {
        if (GraphicsEnvironment.isHeadless()) return
        val image = BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(4, 8, (0xFF shl 24) or (0x12 shl 16) or (0x34 shl 8) or 0x56)

        val written = DesktopImageClipboard.write(image)
        assertTrue(written, "clipboard setContents must succeed on desktop")

        val contents = java.awt.Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
        assertNotNull(contents)
        assertTrue(contents.isDataFlavorSupported(DataFlavor.imageFlavor))
        val readBack = contents.getTransferData(DataFlavor.imageFlavor) as java.awt.Image
        assertEquals(32, readBack.getWidth(null))
        assertEquals(16, readBack.getHeight(null))
    }
}
