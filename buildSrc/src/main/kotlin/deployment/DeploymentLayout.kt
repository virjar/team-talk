package deployment

import java.io.File

/**
 * 部署布局的路径唯一事实源。
 *
 * 两个状态目录按所选配置严格分离（2026-09 定案）：
 *  - 公共部署（`buildSrc/deployment/Deployment.kt`）：生成的部署状态——
 *    `deployment.secrets`、`tcp-tls/`——也放在 `buildSrc/deployment/` 下，
 *    除 `Deployment.kt` 外全部被 Git 忽略；
 *  - 私有部署（`buildSrc/deployment-local/Deployment.kt` 存在）：私有配置、
 *    凭据、TLS 材料与 vendor SDK 全部收在 `buildSrc/deployment-local/`，
 *    整目录被 Git 忽略；交接部署时整体拷贝该目录即可。
 *
 * 路径只能经由本文件引用；buildSrc/build.gradle.kts 在源码编译前运行，
 * 只能各自内联同一相对路径。
 */
const val DEPLOYMENT_LOCAL_DIRECTORY: String = "buildSrc/deployment-local"

/** 公共部署的配置目录与生成状态目录；Deployment.kt 之外的一切被 Git 忽略。 */
const val DEPLOYMENT_PUBLIC_DIRECTORY: String = "buildSrc/deployment"

/** 私有部署配置入口；存在该文件时 buildSrc 编译整个 local 目录替换公版配置。 */
fun deploymentLocalConfigurationFile(rootDir: File): File =
    File(File(rootDir, DEPLOYMENT_LOCAL_DIRECTORY), "Deployment.kt")

/**
 * 部署生成状态的根目录：私有配置存在时跟随 deployment-local，否则落在
 * buildSrc/deployment。凭据与 TLS 材料与所选配置同侧，绝不交叉。
 */
fun deploymentStateDirectory(rootDir: File): File =
    if (deploymentLocalConfigurationFile(rootDir).isFile) {
        File(rootDir, DEPLOYMENT_LOCAL_DIRECTORY)
    } else {
        File(rootDir, DEPLOYMENT_PUBLIC_DIRECTORY)
    }

/** 服务端部署凭据：首次部署生成，升级时以远端 env.sh 为权威回写。 */
fun deploymentSecretsFile(rootDir: File): File =
    File(deploymentStateDirectory(rootDir), "deployment.secrets")

/** generateTcpTlsCertificate 生成或校验复用的证书目录；独立于 build/，clean 不触发换证。 */
fun tcpTlsCertificateDirectory(rootDir: File): File =
    File(deploymentStateDirectory(rootDir), "tcp-tls")

/** 私有配置 server.tcp.tls.certificateFile 的便捷引用；只指向公共证书，绝不指向私钥。 */
fun tcpTlsCertificateFile(rootDir: File): File =
    File(tcpTlsCertificateDirectory(rootDir), "certificate.pem")
