package com.virjar.tk.shared.agent

import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.JvmClientDataLease
import com.virjar.tk.shared.client.LocalCacheDiagnostics
import com.virjar.tk.shared.client.LocalCacheDiagnosticLayout
import com.virjar.tk.shared.client.LocalCacheDiagnosticOwner
import com.virjar.tk.shared.client.LocalCacheCompaction
import com.virjar.tk.shared.client.decodeTcpTlsCertificateBase64
import com.virjar.tk.shared.client.prepareJvmClientDataVersion
import com.virjar.tk.shared.client.privateAtomicTextFileStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Base64

@Serializable
internal data class AgentLaunchSettings(
    val host: String,
    val port: Int,
    val serverUrl: String,
    val api: String,
    val tcpCertificateBase64: String? = null,
) {
    val deployment: DeploymentIdentity get() = DeploymentIdentity.from(host, port, serverUrl)
}

/** Endpoint configuration is independent of refresh credentials and survives ordinary bundle upgrades. */
internal object HeadlessConfiguration {
    private const val FILE_NAME = "agent-settings.json"
    private const val MAX_BYTES = 128L * 1024

    fun dataDir(options: Map<String, String>, env: Map<String, String> = System.getenv()): File =
        File(options["data-dir"] ?: env["TK_AGENT_DIR"] ?: "${System.getProperty("user.home")}/.tt-agent")

    fun load(dataDir: File): AgentLaunchSettings? = store(dataDir).readText(MAX_BYTES)?.let {
        Json.decodeFromString<AgentLaunchSettings>(it).also(::validate)
    }

    fun resolve(options: Map<String, String>, env: Map<String, String>, saved: AgentLaunchSettings?): AgentLaunchSettings {
        val host = options["host"] ?: env["TK_HOST"] ?: saved?.host ?: "im.virjar.com"
        val port = (options["port"] ?: env["TK_PORT"])?.toIntOrNull()
            ?: if (options.containsKey("port") || env.containsKey("TK_PORT")) error("Invalid TCP port") else saved?.port ?: 5100
        val serverUrl = options["server-url"] ?: env["TK_SERVER_URL"] ?: saved?.serverUrl
        val deployment = serverUrl?.let { DeploymentIdentity.from(host, port, it) }
            ?: DeploymentIdentity.fromTcpWithDefaultHttp(host, port)
        return AgentLaunchSettings(
            deployment.tcpHost, deployment.tcpPort, deployment.httpBaseUrl,
            AgentBindPolicy.parse(options["api"] ?: saved?.api ?: "127.0.0.1:8600").display,
            System.getProperty("teamtalk.tcp.certificate.base64") ?: saved?.tcpCertificateBase64,
        ).also(::validate)
    }

    fun requireSameDeployment(dataDir: File, settings: AgentLaunchSettings) {
        val previous = AgentCredentials.diagnostics(dataDir)?.deploymentFingerprint
        require(previous.isNullOrEmpty() || previous == settings.deployment.fingerprint) {
            "This data directory belongs to another deployment; use its saved endpoints or a new data directory"
        }
    }

    fun execute(command: String, args: List<String>) {
        val options = parseOptions(args)
        val allowed = when (command) {
            "configure" -> setOf("data-dir", "host", "port", "server-url", "api", "tcp-certificate")
            "export-cli-token" -> setOf("data-dir", "token-file")
            "doctor" -> setOf("data-dir", "cache-root", "cache-layout")
            "compact-cache" -> setOf("cache-root", "database")
            else -> error("Unknown configuration command")
        }
        require(options.keys.all { it in allowed }) { "Unknown configuration option" }
        if (command == "compact-cache") {
            val root = requireNotNull(options["cache-root"]) { "--cache-root is required" }
            val database = requireNotNull(options["database"]) { "--database is required; select its relative path from doctor" }
            println(Json.encodeToString(LocalCacheCompaction.compact(File(root), database)))
            return
        }
        val dataDir = dataDir(options)
        if (command == "doctor") {
            val cacheRoot = options["cache-root"]
            require(cacheRoot != null || "cache-layout" !in options) { "--cache-layout requires --cache-root" }
            require(cacheRoot == null || "data-dir" !in options) { "Use either --data-dir or --cache-root" }
            if (cacheRoot != null) {
                println(Json.encodeToString(LocalCacheDiagnostics.inspect(File(cacheRoot), cacheLayout(options))))
            } else println(doctor(dataDir))
            return
        }
        if (command == "export-cli-token") {
            require(Files.isDirectory(dataDir.toPath(), NOFOLLOW_LINKS)) { "Agent data directory does not exist" }
            AgentDataDirectoryPolicy.openRuntime(dataDir)
            val settings = load(dataDir) ?: error("Save agent endpoints with configure before exporting the CLI token")
            val token = AgentCredentials.activeLocalToken(dataDir, settings.deployment)
            val destination = File(requireNotNull(options["token-file"]) { "--token-file is required" })
            HeadlessTokenFile.createOrReuse(destination) { token }.also {
                require(it == token) { "The destination already contains a different token; use another file" }
            }
            println("CLI token saved to ${destination.absolutePath}; set TT_CLI_CONFIG to this file and TT_API=${settings.api}")
            return
        }
        AgentDataDirectoryPolicy.openRuntime(dataDir)
        JvmClientDataLease.acquire(dataDir).use {
            prepareJvmClientDataVersion(dataDir)
            var settings = resolve(options, emptyMap(), load(dataDir))
            options["tcp-certificate"]?.let { path ->
                val certificate = File(path)
                require(certificate.isFile && certificate.length() in 1..65_536) { "Certificate must be a PEM file of at most 64 KiB" }
                val bytes = certificate.inputStream().use { it.readNBytes(65_537) }
                require(bytes.size <= 65_536 && bytes.toString(Charsets.UTF_8).contains("-----BEGIN CERTIFICATE-----")) {
                    "Certificate must contain a public PEM certificate"
                }
                require(!bytes.toString(Charsets.UTF_8).contains("PRIVATE KEY")) { "Only the public certificate belongs in agent settings" }
                settings = settings.copy(tcpCertificateBase64 = Base64.getEncoder().encodeToString(bytes))
            }
            requireSameDeployment(dataDir, settings)
            store(dataDir).replaceText(Json.encodeToString(AgentLaunchSettings.serializer(), settings), MAX_BYTES)
            println("Agent configuration saved to ${File(dataDir, FILE_NAME).absolutePath}")
        }
    }

    private fun cacheLayout(options: Map<String, String>): LocalCacheDiagnosticLayout = when (options["cache-layout"] ?: "jvm") {
        "jvm" -> LocalCacheDiagnosticLayout.JVM
        "android" -> LocalCacheDiagnosticLayout.ANDROID
        else -> error("--cache-layout must be jvm or android")
    }

    fun doctor(dataDir: File) = buildJsonObject {
        put("client", HeadlessRuntime.facts())
        put("dataDir", dataDir.absolutePath)
        put("localCacheDiagnostics", Json.encodeToJsonElement(LocalCacheDiagnostics.inspect(dataDir)))
        if (!Files.exists(dataDir.toPath(), NOFOLLOW_LINKS)) {
            put("configuration", "not-configured")
            put("authentication", "not-configured")
        } else {
            AgentDataDirectoryPolicy.openRuntime(dataDir) // Existing directories are validated, never repaired.
            val settings = load(dataDir)
            put("configuration", if (settings == null) "runtime-options" else "saved")
            settings?.let {
                put("tcp", it.deployment.tcpAuthority)
                put("serverUrl", it.serverUrl)
                put("api", it.api)
                put("tcpCertificateConfigured", it.tcpCertificateBase64 != null)
                requireSameDeployment(dataDir, it)
            }
            val credentials = AgentCredentials.diagnostics(dataDir)
            put("authentication", credentials?.state ?: "not-configured")
            credentials?.let {
                put("deploymentFingerprint", it.deploymentFingerprint)
                put("uid", it.uid)
            }
        }
    }

    private fun validate(settings: AgentLaunchSettings) {
        settings.deployment
        AgentBindPolicy.parse(settings.api)
        settings.tcpCertificateBase64?.let {
            require(it.length <= 90_000) { "Configured certificate exceeds its size budget" }
            val pem = decodeTcpTlsCertificateBase64(it).orEmpty()
            require(pem.contains("-----BEGIN CERTIFICATE-----") && !pem.contains("PRIVATE KEY")) { "Invalid public TCP certificate" }
        }
    }

    private fun store(dataDir: File) = privateAtomicTextFileStore(dataDir, emptyList(), FILE_NAME)

    private fun parseOptions(args: List<String>): Map<String, String> {
        require(args.size % 2 == 0) { "Options require --name value pairs" }
        val options = linkedMapOf<String, String>()
        args.chunked(2).forEach { (flag, value) ->
            require(flag.startsWith("--") && value.isNotBlank() && !value.startsWith("--")) { "Options require --name value pairs" }
            require(options.put(flag.removePrefix("--"), value) == null) { "Duplicate configuration option" }
        }
        return options
    }
}
