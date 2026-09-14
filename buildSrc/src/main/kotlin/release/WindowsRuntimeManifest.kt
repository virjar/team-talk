package release

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document

/**
 * 只修改 staging 中 Java 启动器的进程代码页。JBR 的 javaw 会把 Unicode argv 转成 CP_ACP；
 * 父 Launch4j 的 UTF-8 清单不能替代子进程清单，否则中文在英文 Windows 上会变成问号/通配符。
 *
 * 不重建 PE、不移动任何节：空 XML 元素等价压缩后，更新必须放得进原 RT_MANIFEST/1 槽。
 * 原厂商签名随修改失效，只移除文件尾证书；未知布局或容量不足直接拒绝，留待升级 JBR 时复核。
 */
internal object WindowsRuntimeManifest {
    private const val ASSEMBLY = "urn:schemas-microsoft-com:asm.v3"
    private const val WINDOWS_SETTINGS = "http://schemas.microsoft.com/SMI/2019/WindowsSettings"

    /** 已由厂商声明 UTF-8 时原字节返回，保留其签名。调用方必须传入自己拥有的 staging 副本。 */
    fun enableUtf8(launcher: File): Boolean {
        val source = launcher.readBytes()
        val pe = ManifestSlot(source)
        val originalXml = source.copyOfRange(pe.offset, pe.offset + pe.size)
        val xml = originalXml.toString(Charsets.UTF_8)
        require(xml.toByteArray(Charsets.UTF_8).contentEquals(originalXml)) { "Java launcher manifest is not UTF-8" }
        val original = parse(xml)
        val existing = original.getElementsByTagNameNS(WINDOWS_SETTINGS, "activeCodePage")
        if (existing.length == 1 && existing.item(0).textContent.trim() == "UTF-8") return false
        require(existing.length == 0) { "Java launcher declares an unsupported activeCodePage" }
        val settings = original.getElementsByTagNameNS(ASSEMBLY, "windowsSettings")
        require(settings.length == 1) { "Expected one Java launcher windowsSettings element" }
        val closing = "</${settings.item(0).nodeName}>"
        require(xml.split(closing).size == 2) { "Unsupported Java launcher manifest layout" }
        val compact = xml.trimEnd().replace(Regex("<([\\w:]+)([^<>]*)></\\1>"), "<$1$2/>")
        val updated = compact.replace(closing,
            "<activeCodePage xmlns=\"$WINDOWS_SETTINGS\">UTF-8</activeCodePage>$closing")
        val checked = parse(updated)
        val added = checked.getElementsByTagNameNS(WINDOWS_SETTINGS, "activeCodePage")
        require(added.length == 1 && added.item(0).textContent == "UTF-8")
        added.item(0).parentNode.removeChild(added.item(0))
        require(original.isEqualNode(checked)) { "Java launcher manifest changed beyond activeCodePage" }
        val encoded = updated.toByteArray(Charsets.UTF_8)
        require(encoded.size <= pe.size) {
            "Java launcher UTF-8 manifest needs ${encoded.size} bytes; original slot has ${pe.size}. Review the selected JBR."
        }

        val output = source.copyOf(pe.unsignedSize)
        output.fill(' '.code.toByte(), pe.offset, pe.offset + pe.size)
        encoded.copyInto(output, pe.offset)
        // 安全目录使用文件偏移，不是 RVA。清除失效证书引用，保留全部映射节和其余资源。
        output.fill(0, pe.securityDirectory, pe.securityDirectory + 8)
        output.fill(0, pe.checksumOffset, pe.checksumOffset + 4)
        var checksum = 0L
        for (index in output.indices step 2) {
            val word = (output[index].toInt() and 0xFF) or
                ((if (index + 1 < output.size) output[index + 1].toInt() and 0xFF else 0) shl 8)
            checksum += word
            checksum = (checksum and 0xFFFF) + (checksum ushr 16)
        }
        checksum = (checksum and 0xFFFF) + (checksum ushr 16) + output.size
        ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).putInt(pe.checksumOffset, checksum.toInt())
        launcher.writeBytes(output)
        return true
    }

    private fun parse(xml: String): Document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))

    /** 定位一个固定资源及允许修改的头字段；不提供通用 PE 编辑能力。 */
    private class ManifestSlot(private val bytes: ByteArray) {
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private fun range(offset: Int, size: Int) {
            require(offset >= 0 && size >= 0 && offset.toLong() + size <= bytes.size) { "Truncated Java launcher PE" }
        }
        private fun word(offset: Int): Int { range(offset, 2); return buffer.getShort(offset).toInt() and 0xFFFF }
        private fun dword(offset: Int): Int { range(offset, 4); return buffer.getInt(offset) }
        private fun position(offset: Int): Int = dword(offset).also { require(it >= 0) { "Unsupported PE offset" } }
        val checksumOffset: Int
        val securityDirectory: Int
        val unsignedSize: Int
        val offset: Int
        val size: Int

        init {
            require(word(0) == 0x5A4D) { "Java launcher is not an MZ executable" }
            val header = position(0x3C)
            require(dword(header) == 0x4550) { "Java launcher PE signature is missing" }
            val optional = header + 24
            val directories = optional + when (word(optional)) {
                0x10B -> 96
                0x20B -> 112
                else -> error("Unsupported Java launcher PE optional header")
            }
            val optionalSize = word(header + 20)
            require(optionalSize >= directories - optional + 40) { "PE data directories are missing" }
            checksumOffset = optional + 64
            securityDirectory = directories + 32
            val sections = optional + optionalSize
            val sectionCount = word(header + 6)
            require(sectionCount in 1..96) { "Unsupported Java launcher PE sections" }
            range(sections, sectionCount * 40)
            var mappedEnd = 0
            for (index in 0 until sectionCount) {
                val section = sections + index * 40
                val start = position(section + 20)
                val length = position(section + 16)
                range(start, length)
                mappedEnd = maxOf(mappedEnd, start + length)
            }
            val certificate = position(securityDirectory)
            val certificateSize = position(securityDirectory + 4)
            unsignedSize = if (certificate == 0 && certificateSize == 0) bytes.size else {
                require(certificate >= mappedEnd && certificateSize > 0 &&
                    certificate.toLong() + certificateSize == bytes.size.toLong()) {
                    "Java launcher certificate is not an isolated file-tail record"
                }
                certificate
            }
            fun fileOffset(rva: Int, length: Int): Int {
                require(rva >= 0 && length >= 0)
                for (index in 0 until sectionCount) {
                    val section = sections + index * 40
                    val relative = rva.toLong() - position(section + 12)
                    if (relative >= 0 && relative + length <= position(section + 16)) {
                        return position(section + 20) + relative.toInt()
                    }
                }
                error("Java launcher resource is outside a mapped PE section")
            }
            val resourcesSize = position(directories + 20)
            val resources = fileOffset(position(directories + 16), resourcesSize)
            fun resourceOffset(relative: Int, length: Int): Int {
                require(relative >= 0 && relative.toLong() + length <= resourcesSize) { "Invalid PE resource offset" }
                return resources + relative
            }
            fun entry(relative: Int, id: Int?): Int {
                val directory = resourceOffset(relative, 16)
                val count = word(directory + 12) + word(directory + 14)
                resourceOffset(relative + 16, count * 8)
                val matches = (0 until count).map { directory + 16 + it * 8 }
                    .filter { id == null || dword(it) == id }
                require(matches.size == 1) { "Expected one Java launcher manifest resource/language" }
                return dword(matches.single() + 4)
            }
            val type = entry(0, 24)
            require(type < 0) { "Invalid RT_MANIFEST directory" }
            val name = entry(type and Int.MAX_VALUE, 1)
            require(name < 0) { "Invalid RT_MANIFEST/1 directory" }
            val data = entry(name and Int.MAX_VALUE, null)
            require(data >= 0) { "Invalid RT_MANIFEST language data" }
            val descriptor = resourceOffset(data, 16)
            size = position(descriptor + 4)
            require(size in 1..65536) { "Unsupported Java launcher manifest size" }
            offset = fileOffset(position(descriptor), size)
            require(offset >= resources && offset.toLong() + size <= resources.toLong() + resourcesSize) {
                "Java launcher manifest is outside the resource directory"
            }
        }
    }
}
