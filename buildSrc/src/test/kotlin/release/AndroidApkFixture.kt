package release

import deployment.ProcessOutputMode
import deployment.ProcessSpec
import deployment.runCheckedProcess
import java.io.File
import java.nio.file.Files

/** Tiny real binary manifest/resources APKs; no application compilation, signing or network required. */
internal fun writeAndroidApkFixture(
    target: File,
    producer: BundleIdentity,
    actual: BundleIdentity = producer,
    buildIdentity: String = producer.buildIdentity,
    legacyProducer: Boolean = false,
    localizedLabel: String? = null,
    launcherLabel: String? = null,
    canonicalConfig: String = producer.deployment.toCanonicalJson(),
) {
    val temporary = Files.createTempDirectory("teamtalk-apk-fixture-").toFile()
    try {
        val aapt2 = androidAapt2()
        val androidJar = File(androidSdkDirectory(), "platforms").listFiles().orEmpty()
            .sortedByDescending { it.name.removePrefix("android-").toIntOrNull() ?: -1 }
            .map { File(it, "android.jar") }.firstOrNull(File::isFile)
            ?: error("Android APK integration tests require an installed SDK platform")
        val assets = File(temporary, "assets").apply { mkdirs() }
        val properties = File(assets, "teamtalk-build.properties")
        if (legacyProducer) {
            properties.writeText("artifactType=android-apk\nversion=${producer.version.name}\nbuildIdentity=$buildIdentity\n")
        } else {
            writeAndroidReleaseIdentity(properties, producer.version.name, buildIdentity, canonicalConfig)
        }
        fun labelResource(folder: String, label: String) {
            val escaped = label.replace("\\", "\\\\").replace("\"", "\\\"").xmlText()
            File(temporary, "res/$folder/strings.xml").apply {
                parentFile.mkdirs()
                writeText("<resources><string name=\"app_name\">\"$escaped\"</string></resources>")
            }
        }
        labelResource("values", actual.client.displayName)
        localizedLabel?.let { labelResource("values-zh-rCN", it) }
        val manifest = File(temporary, "AndroidManifest.xml").apply {
            writeText("""
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="${actual.client.androidApplicationId}"
                    android:versionCode="${actual.version.buildNumber + 1}" android:versionName="${actual.version.name}">
                  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/>
                  <application android:label="@string/app_name">
                    <activity android:name="com.example.MainActivity" android:exported="true"
                      ${launcherLabel?.let { "android:label=\"${it.xmlText()}\"" }.orEmpty()}>
                      <intent-filter>
                        <action android:name="android.intent.action.MAIN"/>
                        <category android:name="android.intent.category.LAUNCHER"/>
                      </intent-filter>
                    </activity>
                  </application>
                </manifest>
            """.trimIndent())
        }
        val compiled = File(temporary, "compiled.zip")
        fun aapt(vararg arguments: String) = runCheckedProcess(ProcessSpec(
            label = "Build a binary Android APK identity fixture",
            arguments = listOf(aapt2.absolutePath) + arguments,
            timeoutMillis = 30_000L,
            outputMode = ProcessOutputMode.CAPTURE,
        ))
        aapt("compile", "--dir", File(temporary, "res").absolutePath, "-o", compiled.absolutePath)
        target.parentFile.mkdirs()
        aapt("link", "-I", androidJar.absolutePath, "--manifest", manifest.absolutePath,
            "-A", assets.absolutePath, "-o", target.absolutePath, compiled.absolutePath)
    } finally {
        temporary.deleteRecursively()
    }
}

private fun String.xmlText(): String = replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
