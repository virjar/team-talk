package release

import java.io.File
import kotlin.test.Test
import org.junit.Assume.assumeTrue

/**
 * 本机格式校验（仅 macOS 运行）：用 [MacCodeSigner] 重签入库的启动器与真实 bundle，
 * 产物落到 /tmp 并调用系统 codesign 验证。这是格式实现的迭代工具，不是 CI 断言。
 */
class MacCodeSignerManualTest {

    @Test
    fun `re-sign bundled launcher passes system codesign verification`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"))
        assumeTrue(File("/usr/bin/codesign").canExecute())
        val resource = javaClass.classLoader.getResourceAsStream("macos-launcher/TeamTalkLauncher")!!
        val original = resource.readBytes()
        // 裸二进制（非 bundle）场景：Info.plist 槽为全零。
        val signed = MacCodeSigner.sign(original, "com.virjar.tk", null, null)

        val out = File("/tmp/mac-signer-probe/TeamTalkLauncher")
        out.parentFile.mkdirs()
        out.writeBytes(signed)
        check(out.setExecutable(true, false))

        val verify = ProcessBuilder("/usr/bin/codesign", "-vvv", out.absolutePath)
            .redirectErrorStream(true).start()
        val output = verify.inputStream.bufferedReader().readText()
        check(verify.waitFor() == 0) { "codesign verify failed:\n$output" }
    }

    @Test
    fun `re-sign assembled app bundle passes system codesign verification`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"))
        assumeTrue(File("/usr/bin/codesign").canExecute())
        val staging = File(
            System.getProperty("mac.bundle.under.test")
                ?: "/Users/virjar/git/tk/team-talk/client/desktop/build/desktop-shell/macos-amd64/staging/TeamTalk.app",
        )
        assumeTrue(staging.isDirectory)
        val copy = File("/tmp/mac-signer-probe/TeamTalk-jvm-signed.app")
        copy.parentFile.mkdirs()
        staging.copyRecursively(copy, overwrite = true)

        MacAppBundleSigner.sign(File(copy, "Contents"), "com.virjar.tk")

        val verify = ProcessBuilder("/usr/bin/codesign", "--verify", "--deep", "--strict", "--verbose=2", copy.absolutePath)
            .redirectErrorStream(true).start()
        val output = verify.inputStream.bufferedReader().readText()
        check(verify.waitFor() == 0) { "codesign bundle verify failed:\n$output" }
        println(output)
    }
}
