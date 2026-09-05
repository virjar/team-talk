package com.virjar.tk.shared.agent

import com.virjar.tk.shared.client.DeploymentIdentity
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** 供特权写入方与确定性安全测试使用的纯安装结果。 */
data class AgentServiceInstallPlan(
    val unit: String,
    val dataDirectory: String,
    val serviceUser: String,
    val deploymentIdentity: DeploymentIdentity,
)

/** 纯准备结果：不写入 unit，也不接受任何认证材料。 */
data class AgentServiceDataPlan(
    val dataDirectory: String,
    val serviceUser: String,
)

/** 无头 agent 的 Linux systemd 安装器（见 doc/05-clients/headless.md）。 */
object AgentService {

    private const val UNIT_NAME = "tt-agent"
    private const val DEFAULT_DATA_DIRECTORY = "/var/lib/tt-agent"
    private const val DEFAULT_SERVICE_USER = "tt-agent"
    private val unitPath = File("/etc/systemd/system/$UNIT_NAME.service")
    private val systemCommands: AgentSystemCommands by lazy { JvmAgentSystemCommands() }

    // jar 安装在 <appHome>/lib/*.jar。
    private val appHome: String
        get() = File(AgentService::class.java.protectionDomain.codeSource.location.toURI())
            .parentFile?.parent?.let { File(it).absolutePath } ?: "/opt/tt-agent"

    fun install(args: List<String>) {
        if (!requireLinux("install")) return
        requireRootInstaller()
        val plan = buildInstallPlan(args, appHome)
        val identity = validateServiceIdentity(plan.serviceUser, resolveServiceIdentity(plan.serviceUser))
        val dataDirectory = AgentDataDirectoryPolicy.openPreparedForService(File(plan.dataDirectory), identity)
        AgentCredentials.requireActiveForInstall(dataDirectory, plan.deploymentIdentity)
        writeUnitAtomically(plan.unit)
        println("[install] unit 已写入 $unitPath（运行用户 ${plan.serviceUser}）")
        println("[install] ACTIVE refresh 凭据已验证；可执行：")
        println("  systemctl daemon-reload && systemctl enable --now $UNIT_NAME")
        println("  journalctl -u $UNIT_NAME -f   # 仅查看运行状态")
    }

    /** 仅限 root 的第一步：创建/验证一个专用叶子，但绝不写入 unit。 */
    fun prepareData(args: List<String>) {
        if (!requireLinux("prepare-service-data")) return
        requireRootInstaller()
        val plan = buildDataPlan(args)
        val identity = validateServiceIdentity(plan.serviceUser, resolveServiceIdentity(plan.serviceUser))
        AgentDataDirectoryPolicy.prepareForService(File(plan.dataDirectory), identity)
        println(
            "[prepare-service-data] prepared ${plan.dataDirectory} for ${plan.serviceUser}; " +
                "complete foreground bootstrap before install",
        )
    }

    internal fun buildDataPlan(args: List<String>): AgentServiceDataPlan {
        val opts = AgentCli.parse(args.toTypedArray())
        val unknown = opts.keys - PREPARE_ALLOWED_OPTIONS
        require(unknown.isEmpty()) {
            "prepare-service-data only accepts --data-dir and --service-user"
        }
        val dataDirectory = validateDataDirectory(opts["data-dir"] ?: DEFAULT_DATA_DIRECTORY)
        val serviceUser = validateServiceUser(opts["service-user"] ?: DEFAULT_SERVICE_USER)
        return AgentServiceDataPlan(dataDirectory, serviceUser)
    }

    internal fun buildInstallPlan(args: List<String>, resolvedAppHome: String): AgentServiceInstallPlan {
        val opts = AgentCli.parse(args.toTypedArray())
        require("pass" !in opts) {
            "install 禁止 --pass；请先以受控前台输入完成 ACTIVE bootstrap"
        }
        require("register" !in opts) {
            "install 禁止 --register；请先以前台一次性注册方式写入 dataDir，再安装服务"
        }
        require("reauth" !in opts) {
            "install 禁止 --reauth；请先以前台一次性方式恢复 ACTIVE refresh"
        }
        require("token" !in opts) { "install 不接受本地 API 凭据" }
        require("user" !in opts && "prefix" !in opts) {
            "install 不把账号信息写入 unit；请先完成前台 ACTIVE bootstrap"
        }
        val unknown = opts.keys - ALLOWED_OPTIONS
        require(unknown.isEmpty()) { "Unknown install options: ${unknown.sorted().joinToString()}" }

        val host = (opts["host"] ?: "im.virjar.com").validatedValue("host")
        val port = (opts["port"] ?: "5100").toIntOrNull()
        require(port != null && port in 1..65535) { "TCP port must be in 1..65535" }
        val api = AgentBindPolicy.parse(opts["api"] ?: "127.0.0.1:8600").display
        val dataDirectory = validateDataDirectory(opts["data-dir"] ?: DEFAULT_DATA_DIRECTORY)
        val serviceUser = validateServiceUser(opts["service-user"] ?: DEFAULT_SERVICE_USER)
        val home = validateApplicationHome(resolvedAppHome)
        val serverUrl = opts["server-url"]?.let(::validateServerUrl)
        val deploymentIdentity = serverUrl?.let { configured ->
            DeploymentIdentity.from(host, port, configured)
        } ?: DeploymentIdentity.fromTcpWithDefaultHttp(host, port)

        val executableArguments = buildList {
            add("/usr/bin/java")
            add("-cp")
            add("$home/lib/*")
            add("com.virjar.tk.shared.agent.AgentMainKt")
            add("--host")
            add(host)
            add("--port")
            add(port.toString())
            add("--api")
            add(api)
            add("--data-dir")
            add(dataDirectory)
            serverUrl?.let {
                add("--server-url")
                add(it)
            }
        }.joinToString(" ", transform = ::systemdQuote)
        val stateDirectoryDirectives = if (dataDirectory == DEFAULT_DATA_DIRECTORY) {
            "StateDirectory=tt-agent\nStateDirectoryMode=0700"
        } else {
            // 自定义 dataDir 是显式准备的。在这里声明默认 StateDirectory
            // 会给服务一个无关的第二个可写根，并且可能改变它的 owner。
            ""
        }

        val unit = """
[Unit]
Description=TeamTalk headless agent
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=300
StartLimitBurst=5

[Service]
Type=simple
User=$serviceUser
Group=$serviceUser
ExecStart=$executableArguments
WorkingDirectory=${systemdQuote(home)}
Restart=on-failure
RestartSec=5
RestartPreventExitStatus=$AGENT_REAUTH_REQUIRED_EXIT_CODE
UMask=0077
$stateDirectoryDirectives
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
PrivateDevices=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
ReadWritePaths=${systemdQuote(dataDirectory)}
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
""".trim()
        val forbiddenUnitFragments = listOf(
            "password",
            "--pass",
            "--register",
            "--reauth",
            "token",
            "tk_pass",
            "environmentfile",
        )
        require(forbiddenUnitFragments.none { it in unit.lowercase() }) {
            "Refusing to persist authentication material in the systemd unit"
        }
        return AgentServiceInstallPlan(unit, dataDirectory, serviceUser, deploymentIdentity)
    }

    fun uninstall() {
        when (
            AgentServiceUninstaller(
                osName = System.getProperty("os.name").orEmpty(),
                unitPath = unitPath.toPath(),
                commands = systemCommands,
            ).uninstall()
        ) {
            AgentServiceUninstallResult.UNINSTALLED -> {
                println("[uninstall] 已停止、禁用并删除 $unitPath，systemd manager 已刷新")
            }
            AgentServiceUninstallResult.ALREADY_ABSENT -> {
                println("[uninstall] unit 已不存在，systemd manager 已刷新")
            }
        }
    }

    internal fun validateServiceIdentity(
        requestedUser: String,
        identity: AgentUnixIdentity?,
    ): AgentUnixIdentity {
        val resolved = requireNotNull(identity) { "systemd service user does not exist" }
        require(resolved.userName == requestedUser) { "Resolved service user does not match the request" }
        require(resolved.uid > 0 && resolved.gid > 0) { "systemd service identity must not map to uid/gid 0" }
        require(resolved.primaryGroupName == requestedUser) {
            "systemd service user must have a same-name primary group"
        }
        return resolved
    }

    private fun writeUnitAtomically(unit: String) {
        val path = unitPath.toPath()
        require(!Files.isSymbolicLink(path)) { "Refusing to replace a symlinked systemd unit" }
        val parent = requireNotNull(path.parent)
        val temporary = Files.createTempFile(parent, ".$UNIT_NAME-", ".service")
        try {
            Files.writeString(
                temporary,
                unit,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            Files.move(
                temporary,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun resolveServiceIdentity(user: String): AgentUnixIdentity? {
        return systemCommands.resolveServiceIdentity(user)
    }

    private fun validateDataDirectory(value: String): String {
        val path = validateAbsolutePath(value, "data directory")
        val normalized = File(path).toPath().normalize()
        require(normalized.parent != null) { "data directory cannot be a filesystem root" }
        require(normalized !in BROAD_DATA_ROOTS) { "data directory cannot be a broad system root" }
        require(BROAD_PRIVATE_ROOTS.none { normalized.startsWith(File(it).toPath()) }) {
            "data directory cannot be placed under a home root"
        }
        return normalized.toString()
    }

    private fun validateApplicationHome(value: String): String {
        val path = File(validateAbsolutePath(value, "application home")).toPath().normalize()
        require(BROAD_PRIVATE_ROOTS.none { path.startsWith(File(it).toPath()) }) {
            "application home must remain readable while ProtectHome is enabled"
        }
        return path.toString()
    }

    private fun validateServiceUser(value: String): String = value.also {
        require(SERVICE_USER.matches(it) && it != "root") {
            "systemd service user must be an explicit non-root account"
        }
    }

    private fun requireLinux(operation: String): Boolean {
        if (System.getProperty("os.name").lowercase().contains("linux")) return true
        System.err.println("[$operation] 仅支持 Linux systemd（当前系统不适用）")
        return false
    }

    private fun requireRootInstaller() {
        require(systemCommands.currentUid() == 0) {
            "systemd service preparation and install must run as root"
        }
    }

    private fun validateAbsolutePath(value: String, label: String): String {
        val checked = value.validatedValue(label)
        val path = File(checked).toPath()
        require(path.isAbsolute) { "$label must be absolute" }
        return path.normalize().toString()
    }

    private fun validateServerUrl(value: String): String {
        val checked = value.validatedValue("server URL")
        val uri = runCatching { URI(checked) }.getOrElse {
            throw IllegalArgumentException("Invalid server URL", it)
        }
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null) {
            "server URL must be an http(s) origin without user info"
        }
        require(uri.rawQuery == null && uri.fragment == null) {
            "server URL must not contain query credentials or a fragment"
        }
        return checked
    }

    private fun String.validatedValue(label: String): String {
        require(isNotBlank() && none(Char::isISOControl)) { "$label contains invalid characters" }
        return this
    }

    private fun systemdQuote(value: String): String {
        require(value.none(Char::isISOControl)) { "systemd argument contains control characters" }
        return "\"${
            value.replace("%", "%%")
                .replace("\$", "\$\$")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
        }\""
    }

    private val ALLOWED_OPTIONS = setOf(
        "host",
        "port",
        "api",
        "data-dir",
        "server-url",
        "service-user",
    )
    private val PREPARE_ALLOWED_OPTIONS = setOf("data-dir", "service-user")
    private val SERVICE_USER = Regex("[a-z_][a-z0-9_-]{0,30}")
    private val BROAD_PRIVATE_ROOTS = setOf("/root", "/home", "/Users")
    private val BROAD_DATA_ROOTS = setOf("/etc", "/var", "/var/lib", "/opt", "/usr", "/tmp")
        .map { File(it).toPath() }
        .toSet()
}
