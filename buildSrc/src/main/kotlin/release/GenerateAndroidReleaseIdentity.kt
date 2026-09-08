package release

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** Registered through AGP's generated-assets API so every variant carries its producer's identity. */
abstract class GenerateAndroidReleaseIdentity : DefaultTask() {
    @get:Input abstract val releaseVersion: Property<String>
    @get:Input abstract val buildIdentity: Property<String>
    /** Canonical effective configuration includes every client and endpoint value. */
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
