package deployment

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * 按部署配置生成厂商通道接入源：
 * 1. OemPushChannels.kt 聚合已配置厂商源集中的通道对象；未配置任何厂商时为空表。
 * 2. OemPushStubComponents.kt 为未配置厂商提供清单引用的占位组件，保证
 *    任意配置组合下清单中的组件类都存在且保持禁用。
 */
abstract class GenerateOemPushChannels : DefaultTask() {
    @get:Input
    abstract val vendors: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    private data class StubComponent(val vendor: String, val declaration: String)

    @TaskAction
    fun generate() {
        val configured = vendors.get()
        val ordered = configured.sortedBy { OemPushVendors.ALL.indexOf(it) }
        val references = ordered.joinToString(", ") { vendor ->
            "${vendor.replaceFirstChar { character -> character.uppercase() }}PushChannel"
        }
        outputDirectory.get().file("com/virjar/tk/android/OemPushChannels.kt").asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
                |package com.virjar.tk.android
                |
                |/** 由部署配置生成；只有已配置厂商的通道进入安装包，未配置时为空表。 */
                |internal val oemPushChannels: List<OemPushChannel> = listOf($references)
                |
                """.trimMargin(),
            )
        }

        val stubs = listOf(
            StubComponent("xiaomi", "class XiaomiPushReceiver : android.content.BroadcastReceiver() {\n    override fun onReceive(context: android.content.Context, intent: android.content.Intent) = Unit\n}"),
            StubComponent("huawei", "class HuaweiPushService : android.app.Service() {\n    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null\n}"),
            StubComponent("honor", "class HonorPushService : android.app.Service() {\n    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null\n}"),
            StubComponent("vivo", "class VivoPushReceiver : android.content.BroadcastReceiver() {\n    override fun onReceive(context: android.content.Context, intent: android.content.Intent) = Unit\n}"),
            StubComponent("meizu", "class MeizuPushReceiver : android.content.BroadcastReceiver() {\n    override fun onReceive(context: android.content.Context, intent: android.content.Intent) = Unit\n}"),
        ).filter { it.vendor !in configured }
        outputDirectory.get().file("com/virjar/tk/android/OemPushStubComponents.kt").asFile.apply {
            parentFile.mkdirs()
            if (stubs.isEmpty()) {
                writeText("package com.virjar.tk.android\n")
            } else {
                writeText(
                    """
                    |package com.virjar.tk.android
                    |
                    |/** 由部署配置生成；未配置厂商的清单组件占位，运行时保持禁用。 */
                    |${stubs.joinToString("\n\n") { it.declaration }}
                    |
                    """.trimMargin(),
                )
            }
        }
    }
}
