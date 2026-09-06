package deployment

import java.io.File

/**
 * 公版默认配置，作为 buildSrc 源码参与编译，支持 IDE 检查、补全和跳转。
 * 私有 clone 创建 buildSrc/deployment-local/Deployment.kt 完整替换本目录；同目录可拆分其他 Kotlin 文件。
 * rootDir 是仓库根目录，私版可在 server.tcp.tls 中用 certificateFile 指向公共证书。
 * 配置只包含非敏感值；密码和私钥不能写在这里。
 */
@Suppress("UNUSED_PARAMETER")
fun deploymentConfiguration(rootDir: File): DeploymentConfig = deployment {
    // 服务入口：TCP 与 SSH 默认跟随 HTTP 主机，下载与更新入口由 HTTP URL 相对计算。
    server {
        http { url = "https://im.virjar.com" }
        tcp { port = 5100 }
    }

    // 管理员本机部署；密码与私钥另行提供。
    deploy {
        directory = "/opt/teamtalk"
        ssh {
            port = 22
            user = "root"
        }
    }

    // 公版安装身份与演示入口；私有版在自己的配置中完整选择身份。
    client {
        allowCustomServer = true
        identity {
            applicationId = "com.virjar.tk"
            displayName = "TeamTalk"
            desktopName = "TeamTalk"
        }
    }
}
