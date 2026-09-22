package deployment

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 部署状态与所选配置严格同侧：私有随 deployment-local，公版随 buildSrc/deployment。 */
class DeploymentLayoutTest {
    private fun tempRoot(): File = createTempDirectory(prefix = "tt-layout-").toFile()

    @Test
    fun `private configuration keeps state under deployment-local`() {
        val root = tempRoot()
        val local = File(root, DEPLOYMENT_LOCAL_DIRECTORY).apply { mkdirs() }
        File(local, "Deployment.kt").writeText("package deployment")

        assertEquals(local, deploymentStateDirectory(root))
        assertEquals(File(local, "deployment.secrets"), deploymentSecretsFile(root))
        assertEquals(File(local, "tcp-tls"), tcpTlsCertificateDirectory(root))
        assertEquals(File(local, "tcp-tls/certificate.pem"), tcpTlsCertificateFile(root))
        root.deleteRecursively()
    }

    @Test
    fun `public configuration keeps state beside the tracked Deployment kt`() {
        val root = tempRoot()
        // 公版 clone：没有 deployment-local/Deployment.kt，状态与公版配置同目录。
        val publicDir = File(root, DEPLOYMENT_PUBLIC_DIRECTORY)

        assertEquals(publicDir, deploymentStateDirectory(root))
        assertEquals(File(publicDir, "deployment.secrets"), deploymentSecretsFile(root))
        assertEquals(File(publicDir, "tcp-tls"), tcpTlsCertificateDirectory(root))
        assertEquals(File(publicDir, "tcp-tls/certificate.pem"), tcpTlsCertificateFile(root))
        assertFalse(deploymentLocalConfigurationFile(root).isFile)
        root.deleteRecursively()
    }

    @Test
    fun `stray deployment-local directory without entry stays public`() {
        val root = tempRoot()
        val local = File(root, DEPLOYMENT_LOCAL_DIRECTORY).apply { mkdirs() }
        // 只有生成状态、没有私有入口的旧目录不切换配置；新状态仍写到公版目录。
        File(local, "deployment.secrets").writeText("legacy")

        assertEquals(File(root, DEPLOYMENT_PUBLIC_DIRECTORY), deploymentStateDirectory(root))
        assertTrue(deploymentSecretsFile(root).parentFile == File(root, DEPLOYMENT_PUBLIC_DIRECTORY))
        root.deleteRecursively()
    }

    @Test
    fun `layout constants keep the repository-relative spelling stable`() {
        assertEquals("buildSrc/deployment-local", DEPLOYMENT_LOCAL_DIRECTORY)
        assertEquals("buildSrc/deployment", DEPLOYMENT_PUBLIC_DIRECTORY)
    }

    private fun createTempDirectory(prefix: String): java.nio.file.Path =
        java.nio.file.Files.createTempDirectory(prefix)
}
