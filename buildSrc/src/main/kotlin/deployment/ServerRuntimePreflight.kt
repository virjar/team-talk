package deployment

import org.gradle.api.GradleException

internal const val MINIMUM_SERVER_JAVA_MAJOR = 21

/**
 * 升级会重新生成 env.sh，其中没有 JAVA_HOME/PATH；只验证新包启动时的服务运行环境。
 * 以 systemd 默认 PATH 和服务覆盖值运行 Gradle launcher 的 Java 选择，不借用 SSH 的 JDK。
 */
internal fun serverJavaVersionCommand(
    defaultServicePath: String,
    serviceEnvironment: Map<String, String> = emptyMap(),
): String {
    require(defaultServicePath.isNotBlank()) { "Systemd default executable path is unavailable" }
    require(serviceEnvironment.keys.all { it == "JAVA_HOME" || it == "PATH" })
    val serviceOverrides = serviceEnvironment.entries.joinToString("\n") { (key, value) ->
        "export $key=${posixShellQuote(value)}"
    }
    val script = """
        unset JAVA_HOME JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
        export PATH=${posixShellQuote(defaultServicePath)}
        $serviceOverrides
        if [ -n "${'$'}JAVA_HOME" ]; then
            if [ -x "${'$'}JAVA_HOME/jre/sh/java" ]; then
                teamtalk_java="${'$'}JAVA_HOME/jre/sh/java"
            else
                teamtalk_java="${'$'}JAVA_HOME/bin/java"
            fi
            test -x "${'$'}teamtalk_java" || exit 1
        else
            teamtalk_java=java
            command -v java >/dev/null 2>&1 || exit 1
        fi
        exec "${'$'}teamtalk_java" -version
    """.trimIndent()
    return "/bin/bash -c ${remoteShellQuote(script)}"
}

/**
 * systemctl show 的 Environment 是带 shell 引号的赋值列表，不执行它。
 * 捕获值可能含数据库密码，因此只保留 Java 选择字段，解析异常也不包含原文。
 */
internal fun parseServiceJavaEnvironment(output: String): Map<String, String> {
    val values = linkedMapOf<String, String>()
    val token = StringBuilder()
    var quote: Char? = null
    var escaped = false
    fun finishToken() {
        val assignment = token.toString()
        val key = assignment.substringBefore('=')
        if (key == "JAVA_HOME" || key == "PATH") {
            val value = assignment.substringAfter('=', "")
            require(value.none { it == '\n' || it == '\r' || it == '\u0000' }) {
                "Unsupported character in TeamTalk systemd Java environment"
            }
            values[key] = value
        }
        token.clear()
    }
    output.forEach { character ->
        when {
            escaped -> {
                token.append(character)
                escaped = false
            }
            character == '\\' && quote != '\'' -> escaped = true
            quote != null -> if (character == quote) quote = null else token.append(character)
            character == '\'' || character == '"' -> quote = character
            character.isWhitespace() -> finishToken()
            else -> token.append(character)
        }
    }
    require(quote == null && !escaped) { "Cannot parse TeamTalk systemd Java environment" }
    finishToken()
    return values
}

/** 只从标准版本行提取数字；输出可能包含 JAVA_TOOL_OPTIONS，绝不能出现在异常里。 */
internal fun requireSupportedServerJava(output: String?, environmentDescription: String): Int {
    val version = output?.lineSequence()?.mapNotNull { line ->
        Regex("^(?:openjdk|java) version \"([0-9]+)(?:\\.([0-9]+))?[^\"]*\".*$")
            .matchEntire(line.trim())
    }?.firstOrNull()
    val first = version?.groupValues?.get(1)?.toIntOrNull()
    val major = if (first == 1) version?.groupValues?.get(2)?.toIntOrNull() else first
    if (major == null || major < MINIMUM_SERVER_JAVA_MAJOR) {
        val reason = when {
            output == null -> "Java is missing or cannot run"
            major == null -> "Java did not report a recognized version"
            else -> "Java $major is too old"
        }
        throw GradleException(
            "Deployment blocked before stopping TeamTalk: $reason in $environmentDescription. " +
                "Install Java $MINIMUM_SERVER_JAVA_MAJOR or newer and check the service's JAVA_HOME/PATH; " +
                "no server files or data have been replaced.",
        )
    }
    return major
}

internal fun preflightServerJavaRuntime(
    host: String,
    user: String,
    port: Int,
    isFirstDeploy: Boolean,
) {
    requireActiveRemoteDeploymentGuard(host, user, port)
    val defaultServicePath = remoteSensitiveCaptureProbe(
        label = "read systemd default executable path",
        host = host,
        user = user,
        port = port,
        command = "/usr/bin/systemd-path search-binaries-default",
        allowedExitCodes = setOf(0),
        timeoutMillis = 20_000L,
    )?.trim() ?: throw GradleException("Cannot read systemd default executable path before deployment")
    val serviceEnvironment = if (isFirstDeploy) {
        emptyMap()
    } else {
        val output = remoteSensitiveCaptureProbe(
            label = "read TeamTalk systemd Java environment",
            host = host,
            user = user,
            port = port,
            command = "systemctl show teamtalk.service --property=Environment --value",
            allowedExitCodes = setOf(0),
            timeoutMillis = 20_000L,
        ) ?: throw GradleException("Cannot read TeamTalk systemd Java environment before deployment")
        parseServiceJavaEnvironment(output)
    }
    val description = "service runtime environment"
    val output = remoteSensitiveCaptureProbe(
        label = "check TeamTalk Java runtime ($description; requires Java $MINIMUM_SERVER_JAVA_MAJOR+)",
        host = host,
        user = user,
        port = port,
        command = serverJavaVersionCommand(defaultServicePath, serviceEnvironment),
        allowedExitCodes = setOf(0, 1, 126, 127),
        timeoutMillis = 20_000L,
    )
    val major = requireSupportedServerJava(output, description)
    println("  Java runtime: $major ($description)")
}
