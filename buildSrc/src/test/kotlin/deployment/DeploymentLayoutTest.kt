package deployment

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** 部署本机状态只允许存在于 buildSrc/deployment-local 一个目录；路径契约在这里锁死。 */
class DeploymentLayoutTest {
    @Test
    fun `all deployment state resolves under the single ignored local directory`() {
        val root = File("/repo").absoluteFile
        val local = File(root, DEPLOYMENT_LOCAL_DIRECTORY)

        assertEquals(File(local, "Deployment.kt"), deploymentLocalConfigurationFile(root))
        assertEquals(File(local, "deployment.secrets"), deploymentSecretsFile(root))
        assertEquals(File(local, "tcp-tls"), tcpTlsCertificateDirectory(root))
        assertEquals(File(local, "tcp-tls/certificate.pem"), tcpTlsCertificateFile(root))
    }

    @Test
    fun `layout constant keeps the repository-relative spelling stable`() {
        assertEquals("buildSrc/deployment-local", DEPLOYMENT_LOCAL_DIRECTORY)
    }
}
