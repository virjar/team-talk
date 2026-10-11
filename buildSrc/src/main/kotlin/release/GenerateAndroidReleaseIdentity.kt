package release

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** 经 AGP 的 generated-assets API 注册，使每个 variant 都携带其生产方身份。 */
abstract class GenerateAndroidReleaseIdentity : DefaultTask() {
    @get:Input abstract val releaseVersion: Property<String>
    @get:Input abstract val buildIdentity: Property<String>
    /** 规范化的生效配置，覆盖全部客户端与端点取值。 */
    @get:Input abstract val deploymentConfigJson: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        writeAndroidReleaseIdentity(
            outputDirectory.get().file("teamtalk-build.properties").asFile,
            releaseVersion.get(), buildIdentity.get(), deploymentConfigJson.get(),
        )
    }
}
