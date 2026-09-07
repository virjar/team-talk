package release.publish

import java.security.Provider
import org.apache.sshd.common.util.security.SecurityProviderChoice
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.common.util.security.SunJCESecurityProviderRegistrar
import org.apache.sshd.common.util.security.bouncycastle.BouncyCastleSecurityProviderRegistrar
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * A Gradle daemon can load several repositories' buildSrc classes. Its global JCA registry outlives
 * each buildSrc classloader, so a named "BC" lookup can return another build's Ed25519 key classes.
 * SSHD must use this build's provider object for both key parsing and cryptographic operations.
 */
internal object SiteSshSecurity {
    init {
        // Preserve SSHD's default preference for the JDK's accelerated AES and HMAC implementations.
        SecurityUtils.registerSecurityProvider(SunJCESecurityProviderRegistrar())
        val provider = BouncyCastleProvider()
        val registrar = object : BouncyCastleSecurityProviderRegistrar() {
            override fun getProviderName(): String = provider.name
            override fun getSecurityProvider(): Provider = provider
            override fun isNamedProviderUsed(): Boolean = false
        }
        // Register before the first SSHD operation: SSHD caches its registrar and algorithm factories.
        check(SecurityUtils.registerSecurityProvider(registrar) === registrar) {
            "Initialize TeamTalk SSH security before using SSHD in this build"
        }
        SecurityUtils.setDefaultProviderChoice(SecurityProviderChoice.toSecurityProviderChoice(provider))
    }

    /** Object initialization serializes concurrent callers without changing the daemon's JCA registry. */
    fun initialize() = Unit
}
