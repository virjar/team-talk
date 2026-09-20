package deployment

import java.io.File

/**
 * 部署本机状态的唯一根目录：私有 Deployment.kt、deployment.secrets、tcp-tls 材料和 OEM vendor SDK
 * 都放在这里。不同团队对仓库的唯一改动就是该目录的内容，交接部署时整体拷贝即可。
 *
 * 路径只能经由本文件引用；buildSrc/build.gradle.kts 在源码编译前运行，只能各自内联同一相对路径。
 */
const val DEPLOYMENT_LOCAL_DIRECTORY: String = "buildSrc/deployment-local"

/** 私有部署配置入口；存在该文件时 buildSrc 编译整个 local 目录替换公版配置。 */
fun deploymentLocalConfigurationFile(rootDir: File): File =
    File(File(rootDir, DEPLOYMENT_LOCAL_DIRECTORY), "Deployment.kt")

/** 服务端部署凭据：首次部署生成，升级时以远端 env.sh 为权威回写。 */
fun deploymentSecretsFile(rootDir: File): File =
    File(File(rootDir, DEPLOYMENT_LOCAL_DIRECTORY), "deployment.secrets")

/** generateTcpTlsCertificate 生成或校验复用的证书目录；独立于 build/，clean 不触发换证。 */
fun tcpTlsCertificateDirectory(rootDir: File): File =
    File(File(rootDir, DEPLOYMENT_LOCAL_DIRECTORY), "tcp-tls")

/** 私有配置 server.tcp.tls.certificateFile 的便捷引用；只指向公共证书，绝不指向私钥。 */
fun tcpTlsCertificateFile(rootDir: File): File =
    File(tcpTlsCertificateDirectory(rootDir), "certificate.pem")
