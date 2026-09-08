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

/** Same effective values feed Android resources, BuildConfig and the producer's embedded identity. */
internal fun androidDeploymentIdentity(canonicalConfig: String): Map<String, String> {
    val config = Json.parseToJsonElement(canonicalConfig).jsonObject
    val client = config.getValue("client").jsonObject
    fun clientField(name: String) = client.getValue(name).jsonPrimitive.content
    return linkedMapOf(
        "deploymentSha256" to sha256(canonicalConfig.toByteArray(Charsets.UTF_8)),
        "applicationId" to clientField("applicationId"),
        "androidApplicationId" to "${clientField("applicationId")}.android",
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
    // Properties performs the required escaping; omit its timestamp and stabilize property order.
    val encoded = StringWriter().also { properties.store(it, null) }.toString()
        .lineSequence().filter { it.isNotEmpty() && !it.startsWith('#') }.sorted().joinToString("\n", postfix = "\n")
    target.parentFile.mkdirs()
    target.writeText(encoded, Charsets.UTF_8)
}

/**
 * format 1 bundles predate embedded deployment fields. Their sealed original bytes remain reusable,
 * with actual manifest/resources checked below. Every newly assembled format 2 bundle requires them.
 */
internal fun verifyAndroidApkIdentity(apk: File, identity: BundleIdentity, allowLegacyProducer: Boolean = false) {
    ZipFile(apk).use { zip ->
        val entry = zip.getEntry("assets/teamtalk-build.properties") ?: error("APK lacks its producer build identity")
        val props = Properties().apply { zip.getInputStream(entry).reader(Charsets.UTF_8).use(::load) }
        require(props.getProperty("buildIdentity") == identity.buildIdentity &&
            props.getProperty("version") == identity.version.name &&
            props.getProperty("artifactType") == "android-apk") { "APK came from a different source revision" }
        val expected = androidDeploymentIdentity(identity.deployment.toCanonicalJson())
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
    // An activity label overrides the application label in launchers. Empty means inherited.
    val launchLabels = Regex("^launchable-activity: name='[^']*' +label='(.*)' +icon=")
    lines.filter { it.startsWith("launchable-activity:") }.forEach { line ->
        val quoted = requireNotNull(launchLabels.find(line)) { "APK launcher label could not be inspected" }.groupValues[1]
        val label = decodeAndroidBadgingLabel("'$quoted'")
        require(label.isEmpty() || label == identity.client.displayName) {
            "APK launcher label differs from the effective configuration"
        }
    }
}

/** aapt2 escapes backslashes and quotes in resource strings, while emitting Unicode directly. */
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

/** Resolve the existing Android SDK; verification never downloads or installs tooling. */
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
