package release.publish

import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.net.URLClassLoader
import java.security.Provider
import java.security.Security
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.apache.sshd.client.SshClient
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.sftp.client.SftpClient
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser

class SiteSshSecurityIsolationTest {
    @Test
    fun `separate builds publish with encrypted Ed25519 keys while another build owns global BC`() {
        val parent = javaClass.classLoader
        val classpath = listOf(
            SiteConnection::class.java, javaClass,
            SshClient::class.java, SecurityUtils::class.java, SftpClient::class.java,
            BouncyCastleProvider::class.java, PEMParser::class.java, ContentInfo::class.java,
        ).map { it.protectionDomain.codeSource.location }.distinct().toTypedArray()
        val original = Security.getProvider("BC")
        val originalPosition = Security.getProviders().indexOf(original) + 1
        IsolatedSshBuild(classpath, parent).use { previousBuild ->
            val foreign = previousBuild.loadClass("org.bouncycastle.jce.provider.BouncyCastleProvider")
                .getConstructor().newInstance() as Provider
            Security.removeProvider("BC")
            Security.addProvider(foreign)
            try {
                // Main and private repositories use different buildSrc classloaders in the same daemon.
                repeat(2) {
                    IsolatedSshBuild(classpath, parent).use { currentBuild ->
                        val thread = Thread.currentThread()
                        val previousContext = thread.contextClassLoader
                        try {
                            thread.contextClassLoader = currentBuild
                            val result = currentBuild.loadClass(SshIsolatedPublicationProbe::class.java.name)
                                .getMethod("publish").invoke(null)
                            assertEquals("new android 0.0.1", result)
                        } catch (failure: InvocationTargetException) {
                            throw failure.targetException
                        } finally {
                            thread.contextClassLoader = previousContext
                        }
                        assertSame(foreign, Security.getProvider("BC"), "Publication must not replace a daemon-wide provider")
                    }
                }
            } finally {
                Security.removeProvider("BC")
                if (original != null) Security.insertProviderAt(original, originalPosition)
            }
        }
    }
}

/** Child-first only for the build's SSH implementation; Kotlin and test infrastructure remain shared. */
private class IsolatedSshBuild(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (!name.startsWith("release.publish.") && !name.startsWith("org.apache.sshd.") &&
            !name.startsWith("org.bouncycastle.")) return super.loadClass(name, resolve)
        synchronized(getClassLoadingLock(name)) {
            val result = findLoadedClass(name) ?: findClass(name)
            if (resolve) resolveClass(result)
            return result
        }
    }
}

/** Invoked through an isolated buildSrc loader; host verification, decryption, auth and SFTP are real. */
object SshIsolatedPublicationProbe {
    @JvmStatic
    fun publish(): String {
        SftpFixture(
            userKey = sshFixtureKey(KeyPairProvider.SSH_ED25519),
            hostKey = sshFixtureKey(KeyPairProvider.SSH_ED25519),
            passphrase = "local regression fixture",
        ).use { fixture ->
            SitePublisher().publish(fixture.publication("0.0.1"), fixture.connection)
            return fixture.downloads.resolve("TeamTalk-android.apk").readText()
        }
    }
}
