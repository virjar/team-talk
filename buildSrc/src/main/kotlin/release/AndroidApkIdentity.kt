package release

import deployment.ProcessOutputMode
import deployment.ProcessSpec
import deployment.runCheckedProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.StringWriter
import java.util.Properties
import java.util.zip.ZipFile

/** 同一组生效取值分别写入 Android 资源、BuildConfig 与生产方嵌入的身份信息。 */
internal fun androidDeploymentIdentity(canonicalConfig: String): Map<String, String> {
    val config = Json.parseToJsonElement(canonicalConfig).jsonObject
    val client = config.getValue("client").jsonObject
    fun clientField(name: String) = client.getValue(name).jsonPrimitive.content
    return linkedMapOf(
        "deploymentSha256" to sha256(canonicalConfig.toByteArray(Charsets.UTF_8)),
        "applicationId" to clientField("applicationId"),
        "androidApplicationId" to (client["androidApplicationId"]?.jsonPrimitive?.content
            ?: "${clientField("applicationId")}.android"),
        "displayName" to clientField("displayName"),
        "desktopName" to clientField("desktopName"),
        "serverUrl" to config.getValue("serverUrl").jsonPrimitive.content,
        "tcpAddress" to config.getValue("tcpAddress").jsonPrimitive.content,
    )
}

internal fun writeAndroidReleaseIdentity(
    target: File,
    version: String,
    buildIdentity: String,
    canonicalConfig: String,
) {
    val properties = Properties().apply {
        setProperty("artifactType", "android-apk")
        setProperty("version", version)
        setProperty("buildIdentity", buildIdentity)
        androidDeploymentIdentity(canonicalConfig).forEach(::setProperty)
    }
    // Properties 负责必要的转义；去掉其时间戳并固定属性顺序。
    val encoded = StringWriter().also { properties.store(it, null) }.toString()
        .lineSequence().filter { it.isNotEmpty() && !it.startsWith('#') }.sorted().joinToString("\n", postfix = "\n")
    target.parentFile.mkdirs()
    target.writeText(encoded, Charsets.UTF_8)
}

/**
 * format 1 bundle 早于内嵌 deployment 字段出现。其密封的原始字节仍可复用，
 * 实际 manifest/资源由下方校验。每个新组装的 format 2 bundle 都必须包含这些字段。
 */
internal fun verifyAndroidApkIdentity(
    apk: File,
    identity: BundleIdentity,
    allowLegacyProducer: Boolean = false,
    canonicalConfig: String = identity.deployment.toCanonicalJson(),
) {
    ZipFile(apk).use { zip ->
        val entry = zip.getEntry("assets/teamtalk-build.properties") ?: error("APK lacks its producer build identity")
        val props = Properties().apply { zip.getInputStream(entry).reader(Charsets.UTF_8).use(::load) }
        require(props.getProperty("buildIdentity") == identity.buildIdentity &&
            props.getProperty("version") == identity.version.name &&
            props.getProperty("artifactType") == "android-apk") { "APK came from a different source revision" }
        val expected = androidDeploymentIdentity(canonicalConfig)
        if (!allowLegacyProducer || expected.keys.any(props::containsKey)) {
            require(expected.all { (key, value) -> props.getProperty(key) == value }) {
                "APK producer deployment identity differs from the effective configuration"
            }
        }
    }
    val result = runCheckedProcess(ProcessSpec(
        label = "Inspect Android APK manifest and application labels",
        arguments = listOf(androidAapt2().absolutePath, "dump", "badging", apk.absolutePath),
        timeoutMillis = 30_000L,
        outputMode = ProcessOutputMode.CAPTURE,
    ))
    require(!result.outputTruncated) { "APK identity inspection output was truncated" }
    verifyAndroidBadging(result.output, identity)
}

internal fun verifyAndroidBadging(badging: String, identity: BundleIdentity) {
    val lines = badging.lineSequence().toList()
    val expectedPackage = "package: name='${identity.client.androidApplicationId}' " +
        "versionCode='${identity.version.buildNumber + 1}' versionName='${identity.version.name}'"
    require(lines.count { it == expectedPackage || it.startsWith("$expectedPackage ") } == 1) {
        "APK manifest package or version differs from the effective configuration"
    }
    val labels = lines.filter { it.startsWith("application-label:") || it.startsWith("application-label-") }
    require(labels.any { it.startsWith("application-label:") } &&
        labels.all { decodeAndroidBadgingLabel(it.substringAfter(':')) == identity.client.displayName }) {
        "APK application label differs from the effective configuration"
    }
    // 启动器中 activity 的 label 会覆盖 application 的 label。空值表示继承。
    val launchLabels = Regex("^launchable-activity: name='[^']*' +label='(.*)' +icon=")
    lines.filter { it.startsWith("launchable-activity:") }.forEach { line ->
        val quoted = requireNotNull(launchLabels.find(line)) { "APK launcher label could not be inspected" }.groupValues[1]
        val label = decodeAndroidBadgingLabel("'$quoted'")
        require(label.isEmpty() || label == identity.client.displayName) {
            "APK launcher label differs from the effective configuration"
        }
    }
}

/** aapt2 会转义资源字符串中的反斜杠与引号，而 Unicode 字符直接输出。 */
private fun decodeAndroidBadgingLabel(quoted: String): String {
    require(quoted.length >= 2 && quoted.first() == '\'' && quoted.last() == '\'') {
        "APK application label could not be inspected"
    }
    val value = quoted.substring(1, quoted.lastIndex)
    return buildString {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char != '\\') append(char) else {
                require(index < value.length) { "APK application label escape is incomplete" }
                append(when (val escaped = value[index++]) {
                    '\\', '\'', '"' -> escaped
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    else -> throw IllegalArgumentException("APK application label escape is unsupported")
                })
            }
        }
    }
}

/** 解析本机已有的 Android SDK；校验绝不下载或安装工具。 */
internal fun androidSdkDirectory(): File {
    val localSdk = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "local.properties") }.firstOrNull(File::isFile)?.let { file ->
            Properties().apply { file.reader(Charsets.UTF_8).use(::load) }.getProperty("sdk.dir")
        }
    return listOfNotNull(localSdk, System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
        .map(::File).firstOrNull { File(it, "build-tools").isDirectory }
        ?: error("Android APK verification requires the existing SDK: set sdk.dir or ANDROID_HOME")
}

internal fun androidAapt2(): File {
    val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "aapt2.exe" else "aapt2"
    return File(androidSdkDirectory(), "build-tools").listFiles().orEmpty()
        .sortedByDescending { it.name.split('.').joinToString(".") { part -> part.padStart(4, '0') } }
        .map { File(it, executable) }.firstOrNull { it.isFile && it.canExecute() }
        ?: error("Android APK verification requires aapt2 in the existing SDK build-tools")
}
