import java.nio.file.Files
import kotlin.test.*

class ArchitectureSourcesTest {
    @Test fun `disabled platform production roots are included without admitting their tests`() {
        val root = Files.createTempDirectory("architecture-source-sets").toFile()
        try {
            for (source in listOf("commonMain", "iosMain", "jvmAndAndroidMain", "iosTest", "commonTest")) {
                root.resolve("client/shared/src/$source/kotlin").mkdirs()
            }
            root.resolve("client/android/src/main/kotlin").mkdirs()
            root.resolve("client/android/src/test/kotlin").mkdirs()
            assertEquals(listOf(
                "client/shared/src/commonMain", "client/shared/src/iosMain", "client/shared/src/jvmAndAndroidMain",
                "client/android/src/main",
            ), architectureMainSourceRoots(root, listOf("client/shared", "client/android")))
        } finally { root.deleteRecursively() }
    }
}
