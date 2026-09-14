package release

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.TreeMap

/**
 * macOS ad-hoc 代码签名的纯 JVM 实现（等价 `codesign -f -s -`，不含 CMS/证书）。
 *
 * 动机：macOS 26 的现代通知 API（UNUserNotificationCenter）只接受带代码签名的应用；
 * 单机交叉打包要求 Windows/Linux 构建机也能产出签名 mac 包，而 Apple codesign 仅
 * 存在于 mac。本实现只覆盖本项目打包所需的形态：
 *
 * - Mach-O 64 位（小端），支持 fat（universal）多 slice，逐 slice 签名并重排；
 * - 入库的启动器二进制已预留 LC_CODE_SIGNATURE（在 mac 上以 codesign 预签过一次），
 *   本实现做“替换签名”，不移动代码布局；
 * - bundle seal（CodeResources）由调用方生成后传入，其哈希进入特殊槽 -3。
 *
 * 格式参考：CMSC / Kernel Code Signing（社区逆向文档）与 codesign 实测行为；
 * 每一步以 macOS `codesign --verify` 的判定为准。
 */
internal object MacCodeSigner {

    private const val MH_MAGIC_64 = 0xfeedfacf.toInt()
    private const val FAT_MAGIC = 0xcafebabe.toInt()
    private const val LC_SEGMENT_64 = 0x19
    private const val LC_CODE_SIGNATURE = 0x1d

    private const val CSMAGIC_EMBEDDED_SIGNATURE = 0xfade0cc0.toInt()
    private const val CSMAGIC_CODEDIRECTORY = 0xfade0c02.toInt()
    private const val CSMAGIC_REQUIREMENTS = 0xfade0c01.toInt()
    private const val CSMAGIC_REQUIREMENT_SET = 0xfade0b01.toInt()
    private const val CSSLOT_CODEDIRECTORY = 0x00000000
    private const val CSSLOT_REQUIREMENTS = 0x00000002
    private const val CS_ADHOC = 0x00000002
    private const val CS_EXECSEG_MAIN_BINARY = 0x00000001L
    private const val CTH_SHA256 = 2

    /** 对一个（可能为 fat 的）Mach-O 重新做 ad-hoc 签名，返回签名后的字节。 */
    fun sign(
        binary: ByteArray,
        identifier: String,
        infoPlist: ByteArray?,
        codeResources: ByteArray?,
    ): ByteArray {
        require(binary.size >= 4)
        val magic = readBe32(binary, 0)
        return if (magic == (FAT_MAGIC.toLong() and 0xFFFFFFFFL)) signFat(binary, identifier, infoPlist, codeResources) else signSlice(binary, identifier, infoPlist, codeResources)
    }

    // ── fat（universal）处理：逐 slice 签名后按对齐重排 ──

    private class FatArch(val cpuType: Int, val cpuSubtype: Int, val offset: Int, val size: Int, val align: Int)

    private fun signFat(binary: ByteArray, identifier: String, infoPlist: ByteArray?, codeResources: ByteArray?): ByteArray {
        val count = readBe32(binary, 4).toInt()
        require(count in 1..16) { "unreasonable fat arch count $count" }
        val archs = ArrayList<FatArch>(count)
        for (i in 0 until count) {
            val base = 8 + i * 20
            archs += FatArch(
                readBe32(binary, base).toInt(), readBe32(binary, base + 4).toInt(),
                readBe32(binary, base + 8).toInt(), readBe32(binary, base + 12).toInt(),
                readBe32(binary, base + 16).toInt(),
            )
        }
        val signed = archs.map { arch -> signSlice(binary.copyOfRange(arch.offset, arch.offset + arch.size), identifier, infoPlist, codeResources) }
        // 头 + arch 表的空间也要参与对齐：fat 头按最大 align 对齐（codesign 保持原 align 值）。
        val maxAlign = archs.maxOf { it.align }.coerceAtLeast(3)
        val headerSize = alignUp(8 + count * 20, 1 shl maxAlign)
        val offsets = IntArray(count)
        var cursor = headerSize
        for (i in archs.indices) {
            val align = 1 shl archs[i].align
            cursor = alignUp(cursor, align)
            offsets[i] = cursor
            cursor += signed[i].size
        }
        val out = ByteArrayOutputStream(cursor)
        out.write(be32(FAT_MAGIC))
        out.write(be32(count))
        for (i in archs.indices) {
            out.write(be32(archs[i].cpuType))
            out.write(be32(archs[i].cpuSubtype))
            out.write(be32(offsets[i]))
            out.write(be32(signed[i].size))
            out.write(be32(archs[i].align))
        }
        while (out.size() < headerSize) out.write(0)
        for (i in archs.indices) {
            while (out.size() < offsets[i]) out.write(0)
            out.write(signed[i])
        }
        return out.toByteArray()
    }

    // ── 单 slice 签名 ──

    private class LoadCommand(val cmd: Int, val cmdsize: Int, val offset: Int)

    private fun signSlice(binary: ByteArray, identifier: String, infoPlist: ByteArray?, codeResources: ByteArray?): ByteArray {
        require(le32(binary, 0) == (MH_MAGIC_64.toLong() and 0xFFFFFFFFL)) { "not a little-endian Mach-O 64 image" }
        val ncmds = le32(binary, 16).toInt()
        var sizeofcmds = le32(binary, 20).toInt()
        val commands = ArrayList<LoadCommand>(ncmds)
        var cursor = 32 // mach_header_64
        repeat(ncmds) {
            val cmd = le32(binary, cursor).toInt()
            val cmdsize = le32(binary, cursor + 4).toInt()
            require(cmdsize >= 8 && cursor + cmdsize <= binary.size) { "corrupt load command at $cursor" }
            commands += LoadCommand(cmd, cmdsize, cursor)
            cursor += cmdsize
        }
        val commandsEnd = 32 + sizeofcmds

        val signatureCommand = commands.firstOrNull { it.cmd == LC_CODE_SIGNATURE }
        val oldDataOff = signatureCommand?.let { le32(binary, it.offset + 8).toInt() } ?: 0

        // 替换语义：截掉旧签名数据；没有签名数据则要求文件在 load commands 后有可用空间。
        val stripped = if (oldDataOff in 1..binary.size) binary.copyOf(oldDataOff) else binary
        val insertCommand = signatureCommand == null
        val codeLimit: Int
        val patchBase: Int
        if (insertCommand) {
            // 追加一条 16 字节 LC_CODE_SIGNATURE：需要 load commands 之后的填充空间。
            val padding = binary.size.let { size ->
                // __TEXT 从文件 0 开始；可用空间 = 第一个 segment 文件数据与 commands 之间的间隙不可知，
                // 简化按“commands 末尾之后 8 字节对齐的空隙”校验：探测到非零字节即拒绝。
                var probe = commandsEnd
                while (probe < binary.size && probe < commandsEnd + 16 && binary[probe] == 0.toByte()) probe++
                probe - commandsEnd
            }
            require(padding >= 16) {
                "Mach-O has no LC_CODE_SIGNATURE and no padding for one; pre-sign the binary once on macOS"
            }
            codeLimit = stripped.size
            patchBase = commandsEnd
            sizeofcmds += 16
        } else {
            codeLimit = stripped.size
            patchBase = signatureCommand!!.offset
        }

        // 空 requirements 的准确形态（实测 codesign 输出）：12 字节 = magic+len+全零 payload。
        val emptyRequirements = run {
            val out = ByteArrayOutputStream(12)
            out.write(be32(CSMAGIC_REQUIREMENTS))
            out.write(be32(12))
            out.write(be32(0))
            out.toByteArray()
        }
        // 0x10000 槽的空占位 blob（8 字节），codesign 始终输出。
        val alternateSlotBlob = blob(CSMAGIC_REQUIREMENT_SET, ByteArray(0))
        val textCommand = commands.firstOrNull { it.cmd == LC_SEGMENT_64 && segmentName(binary, it.offset) == "__TEXT" }
        val execSegLimit = textCommand?.let { le64(binary, it.offset + 32) } ?: 0L

        // LC/linkedit patch 位于代码页内，必须先 patch 再计算页哈希；
        // 而 linkedit 的 filesize 又依赖签名 blob 大小。blob 大小只由页数与槽数决定
        // （与页内容无关），因此两遍即可收敛：先按占位哈希构造确定尺寸，patch 后重算。
        fun build(patched: ByteArray): Pair<ByteArray, Int> {
            val directory = buildCodeDirectory(
                identifier = identifier,
                code = patched,
                codeLimit = codeLimit,
                infoPlist = infoPlist,
                requirements = emptyRequirements,
                codeResources = codeResources,
                execSegLimit = execSegLimit,
            )
            return buildSuperBlob(directory, emptyRequirements, alternateSlotBlob) to directory.size
        }

        val dataOff = alignUp(codeLimit, 16)
        val linkedit = commands.firstOrNull { it.cmd == LC_SEGMENT_64 && segmentName(binary, it.offset) == "__LINKEDIT" }

        fun assemble(superBlob: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(dataOff + superBlob.size)
            out.write(stripped)
            while (out.size() < dataOff) out.write(0)
            out.write(superBlob)
            val result = out.toByteArray()
            writeLe32(result, 20, sizeofcmds)
            writeLe32(result, patchBase + 8, dataOff)
            writeLe32(result, patchBase + 12, superBlob.size)
            if (insertCommand) {
                writeLe32(result, patchBase, LC_CODE_SIGNATURE)
                writeLe32(result, patchBase + 4, 16)
            }
            if (linkedit != null) {
                val fileOff = le64(binary, linkedit.offset + 40)
                val newFileSize = result.size - fileOff
                writeLe64(result, linkedit.offset + 48, newFileSize)
                writeLe64(result, linkedit.offset + 32, alignUp(newFileSize.toInt(), 16384).toLong())
            }
            return result
        }

        // 第一遍确定 blob 尺寸并 patch，第二遍用 patch 后的代码字节重算哈希。
        val first = assemble(build(stripped).first)
        val second = build(first.copyOf(dataOff)).first
        return assemble(second)
    }

    private fun segmentName(binary: ByteArray, commandOffset: Int): String {
        // LC_SEGMENT_64: cmd(4) cmdsize(4) segname[16] ...
        val start = commandOffset + 8
        var last = start
        while (last < start + 16 && binary[last] != 0.toByte()) last++
        return String(binary, start, last - start, Charsets.US_ASCII)
    }

    private fun buildCodeDirectory(
        identifier: String,
        code: ByteArray,
        codeLimit: Int,
        infoPlist: ByteArray?,
        requirements: ByteArray,
        codeResources: ByteArray?,
        execSegLimit: Long,
    ): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val ident = identifier.toByteArray(Charsets.US_ASCII) + 0

        // 特殊槽按内存顺序 -n .. -1 排列（-1 紧邻 code slots）；nSpecialSlots=5 覆盖保留槽。
        val specialSlots = ArrayList<ByteArray>(5)
        // codesign 语义：缺失的内容（无 Info.plist/CodeResources 的裸二进制）槽写全零，
        // 不是空串哈希——以真实 codesign 输出校准。
        // 槽按需添加（codesign 实测）：-2 requirements、-1 Info.plist 恒有（缺失内容为全零），
        // -3 CodeResources 仅 bundle 场景存在。
        if (codeResources != null) specialSlots += sha256.digest(codeResources) // -3
        specialSlots += sha256.digest(requirements)                           // -2
        specialSlots += infoPlist?.let(sha256::digest) ?: ByteArray(32)       // -1

        val pageCount = (codeLimit + 4095) / 4096
        val codeSlots = ByteArray(pageCount * 32)
        var page = 0
        var cursor = 0
        while (cursor < codeLimit) {
            val end = minOf(cursor + 4096, codeLimit)
            sha256.update(code, cursor, end - cursor)
            val hash = sha256.digest()
            System.arraycopy(hash, 0, codeSlots, page * 32, 32)
            page++
            cursor = end
        }

        // v0x20400 头 = 88 字节（blob 头 8 + 结构 80，codeLimit64 为 8 字节）；
        // 布局以真实 codesign 输出校准：identOff-8=80。payload：identifier + special + code slots。
        val headerSize = 88
        val identOffset = headerSize
        val specialOffset = identOffset + ident.size
        val hashOffset = specialOffset + specialSlots.size * 32
        val total = hashOffset + codeSlots.size

        val out = ByteArrayOutputStream(total + 8)
        out.write(be32(CSMAGIC_CODEDIRECTORY))
        out.write(be32(total + 8))                                   // blob length（含 8 字节头）
        out.write(be32(0x20400))                                     // version
        out.write(be32(CS_ADHOC))                                    // flags: ad-hoc
        out.write(be32(hashOffset))                                       // hashOffset，blob 相对
        out.write(be32(identOffset))                                       // identOffset，blob 相对
        out.write(be32(specialSlots.size))                           // nSpecialSlots
        out.write(be32(pageCount))                                   // nCodeSlots
        out.write(be32(codeLimit))                                   // codeLimit
        out.write(32)                                                // hashSize
        out.write(CTH_SHA256)                                        // hashType
        out.write(0)                                                 // platform
        out.write(12)                                                // pageSize = log2(4096)
        out.write(be32(0))                                           // spare2 @40
        out.write(be32(0))                                           // scatterOffset @44 (>=0x20100)
        out.write(be32(0))                                           // teamOffset @48 (>=0x20200)
        out.write(be32(0))                                           // spare3 @52 (>=0x20300)
        out.write(be64(0))                                           // codeLimit64 @56 (>=0x20300，8 字节)
        out.write(be64(0))                                           // execSegBase @64 (>=0x20400)
        out.write(be64(execSegLimit))                                // execSegLimit @72
        out.write(be64(CS_EXECSEG_MAIN_BINARY))                     // execSegFlags @80
        require(out.size() == headerSize) { "header layout drift: ${out.size()}" }
        out.write(ident)
        for (slot in specialSlots) out.write(slot)
        out.write(codeSlots)
        return out.toByteArray()
    }

    private fun buildSuperBlob(directory: ByteArray, requirements: ByteArray, alternate: ByteArray): ByteArray {
        val blobs = listOf(CSSLOT_CODEDIRECTORY to directory, CSSLOT_REQUIREMENTS to requirements, 0x10000 to alternate)
        val total = 12 + blobs.size * 8 + blobs.sumOf { it.second.size }
        val out = ByteArrayOutputStream(total)
        out.write(be32(CSMAGIC_EMBEDDED_SIGNATURE))
        out.write(be32(total))
        out.write(be32(blobs.size))
        var offset = 12 + blobs.size * 8
        for ((type, blob) in blobs) {
            out.write(be32(type))
            out.write(be32(offset))
            offset += blob.size
        }
        for ((_, blob) in blobs) out.write(blob)
        return out.toByteArray()
    }

    private fun blob(magic: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 8)
        out.write(be32(magic))
        out.write(be32(payload.size + 8))
        out.write(payload)
        return out.toByteArray()
    }


    // ── bundle seal：CodeResources（v2 XML plist，字段以真实 codesign 输出校准） ──

    /**
     * CodeResources 覆盖 Contents 下除主可执行、Info.plist/PkgInfo 与 seal 自身外的
     * 全部文件：files 记 Resources/ 的 SHA-1（v1 规则），files2 记全部条目的 SHA-256。
     */
    internal fun buildCodeResources(appContents: File): ByteArray {
        val entries = TreeMap<String, File>()
        walkForSeal(appContents, appContents, entries)
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        val v1 = StringBuilder()
        val v2 = StringBuilder()
        for ((path, file) in entries) {
            val bytes = file.readBytes()
            val hash2 = Base64.getEncoder().encodeToString(sha256.digest(bytes))
            if (path.startsWith("Resources/")) {
                v1.append("\t\t<key>").append(xml(path)).append("</key>\n\t\t<data>\n\t\t")
                    .append(Base64.getEncoder().encodeToString(sha1.digest(bytes)))
                    .append("\n\t\t</data>\n")
            }
            v2.append("\t\t<key>").append(xml(path)).append("</key>\n\t\t<dict>\n")
                .append("\t\t\t<key>hash2</key>\n\t\t\t<data>\n\t\t\t")
                .append(hash2)
                .append("\n\t\t\t</data>\n\t\t</dict>\n")
        }
        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
            .append("<plist version=\"1.0\">\n<dict>\n")
            .append("\t<key>files</key>\n\t<dict>\n").append(v1).append("\t</dict>\n")
            .append("\t<key>files2</key>\n\t<dict>\n").append(v2).append("\t</dict>\n")
            .append(rulesPlist())
            .append("</dict>\n</plist>\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun walkForSeal(root: File, dir: File, entries: TreeMap<String, File>) {
        val children = dir.listFiles()?.sortedBy { it.name } ?: return
        for (child in children) {
            val relative = child.relativeTo(root).path.replace(File.separatorChar, '/')
            when {
                child.isDirectory -> walkForSeal(root, child, entries)
                relative == "Info.plist" || relative == "PkgInfo" -> Unit // 单独槽/忽略
                relative.startsWith("_CodeSignature/") -> Unit          // seal 不含自身
                relative.startsWith("MacOS/") -> Unit                   // 主可执行由签名自身覆盖
                else -> entries[relative] = child
            }
        }
    }

    /** rules/rules2 与 codesign 输出保持一致；校验器按规则决定校验方式与遗漏容忍度。 */
    private fun rulesPlist(): String = buildString {
        append("\t<key>rules</key>\n\t<dict>\n")
        append("\t\t<key>^Resources/</key>\n\t\t<true/>\n")
        append("\t\t<key>^Resources/.*\\.lproj/</key>\n\t\t<dict>\n\t\t\t<key>optional</key>\n\t\t\t<true/>\n\t\t\t<key>weight</key>\n\t\t\t<real>1000</real>\n\t\t</dict>\n")
        append("\t\t<key>^Resources/.*\\.lproj/locversion.plist$</key>\n\t\t<dict>\n\t\t\t<key>omit</key>\n\t\t\t<true/>\n\t\t\t<key>weight</key>\n\t\t\t<real>1100</real>\n\t\t</dict>\n")
        append("\t\t<key>^Resources/Base\\.lproj/</key>\n\t\t<dict>\n\t\t\t<key>weight</key>\n\t\t\t<real>1010</real>\n\t\t</dict>\n")
        append("\t\t<key>^version\\.plist$</key>\n\t\t<true/>\n")
        append("\t</dict>\n")
        append("\t<key>rules2</key>\n\t<dict>\n")
        append("\t\t<key>.*\\.dSYM($|/)</key>\n\t\t<dict>\n\t\t\t<key>weight</key>\n\t\t\t<real>11</real>\n\t\t</dict>\n")
        append("\t\t<key>^(.*/)?\\.DS_Store$</key>\n\t\t<dict>\n\t\t\t<key>omit</key>\n\t\t\t<true/>\n\t\t\t<key>weight</key>\n\t\t\t<real>2000</real>\n\t\t</dict>\n")
        append("\t\t<key>^(Frameworks|SharedFrameworks|PlugIns|Plug-ins|XPCServices|Helpers|MacOS|Library/(Automator|Spotlight|LoginItems))/</key>\n\t\t<dict>\n\t\t\t<key>nested</key>\n\t\t\t<true/>\n\t\t\t<key>weight</key>\n\t\t\t<real>10</real>\n\t\t</dict>\n")
        append("\t\t<key>^.*</key>\n\t\t<true/>\n")
        append("\t\t<key>^Info\\.plist$</key>\n\t\t<dict>\n\t\t\t<key>omit</key>\n\t\t\t<true/>\n\t\t\t<key>weight</key>\n\t\t\t<real>20</real>\n\t\t</dict>\n")
        append("\t\t<key>^PkgInfo$</key>\n\t\t<dict>\n\t\t\t<key>omit</key>\n\t\t\t<true/>\n\t\t\t<key>weight</key>\n\t\t\t<real>20</real>\n\t\t</dict>\n")
        append("\t\t<key>^Resources/</key>\n\t\t<dict>\n\t\t\t<key>weight</key>\n\t\t\t<real>20</real>\n\t\t</dict>\n")
        append("\t\t<key>^Resources/.*\\.lproj/</key>\n\t\t<dict>\n\t\t\t<key>optional</key>\n\t\t\t<true/>\n\t\t\t<key>weight</key>\n\t\t\t<real>1000</real>\n\t\t</dict>\n")
        append("\t\t<key>^Resources/.*\\.lproj/locversion.plist$</key>\n\t\t<dict>\n\t\t\t<key>omit</key>\n\t\t\t<true/>\n\t\t\t<key>weight</key>\n\t\t\t<real>1100</real>\n\t\t</dict>\n")
        append("\t\t<key>^Resources/Base\\.lproj/</key>\n\t\t<dict>\n\t\t\t<key>weight</key>\n\t\t\t<real>1010</real>\n\t\t</dict>\n")
        append("\t\t<key>^[^/]+$</key>\n\t\t<dict>\n\t\t\t<key>nested</key>\n\t\t\t<true/>\n\t\t\t<key>weight</key>\n\t\t\t<real>10</real>\n\t\t</dict>\n")
        append("\t\t<key>^embedded\\.provisionprofile$</key>\n\t\t<dict>\n\t\t\t<key>weight</key>\n\t\t\t<real>20</real>\n\t\t</dict>\n")
        append("\t\t<key>^version\\.plist$</key>\n\t\t<dict>\n\t\t\t<key>weight</key>\n\t\t\t<real>20</real>\n\t\t</dict>\n")
        append("\t</dict>\n")
    }

    private fun xml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ── 二进制工具 ──

    private fun be32(value: Int) = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun be64(value: Long) = byteArrayOf(
        (value ushr 56).toByte(), (value ushr 48).toByte(), (value ushr 40).toByte(), (value ushr 32).toByte(),
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun readBe32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or (bytes[offset + 3].toLong() and 0xFF)

    private fun le32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 3 downTo 0) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }

    private fun le64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }

    private fun writeLe32(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) bytes[offset + i] = (value ushr (8 * i)).toByte()
    }

    private fun writeLe64(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) bytes[offset + i] = (value ushr (8 * i)).toByte()
    }

    internal fun alignUp(value: Int, alignment: Int): Int = if (alignment <= 1) value else (value + alignment - 1) / alignment * alignment
}
