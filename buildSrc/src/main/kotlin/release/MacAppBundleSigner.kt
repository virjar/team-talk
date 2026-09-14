package release

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.TreeMap

/**
 * macOS ad-hoc 代码签名的 bundle 封装：生成 CodeResources seal 并对主可执行签名，
 * 等价 `codesign -f -s -`（不 deep）。跨平台实现，交叉打包不依赖 macOS 工具。
 */
internal object MacAppBundleSigner {

    /**
     * 生成并写入 `Contents/_CodeSignature/CodeResources`，随后对主可执行做 ad-hoc 签名。
     * runtime 等嵌套内容保持厂商原签名，只以资源哈希（hash2）封入 seal。
     */
    fun sign(appContents: File, identifier: String) {
        val codeResources = MacCodeSigner.buildCodeResources(appContents)
        val sealDir = File(appContents, "_CodeSignature")
        val mainExecutable = File(appContents, "MacOS")
            .listFiles()?.singleOrNull { it.isFile }
            ?: throw IllegalArgumentException("expected exactly one main executable under ${appContents.name}/MacOS")
        sealDir.mkdirs()
        File(sealDir, "CodeResources").writeBytes(codeResources)
        val signed = MacCodeSigner.sign(
            binary = mainExecutable.readBytes(),
            identifier = identifier,
            infoPlist = File(appContents, "Info.plist").takeIf { it.isFile }?.readBytes(),
            codeResources = codeResources,
        )
        mainExecutable.writeBytes(signed)
        check(mainExecutable.setExecutable(true, false)) { "Cannot make launcher executable: $mainExecutable" }
    }
}
