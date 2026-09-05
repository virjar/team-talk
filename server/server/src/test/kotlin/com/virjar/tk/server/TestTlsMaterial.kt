package com.virjar.tk.server

import io.ktor.network.tls.certificates.generateCertificate
import io.ktor.network.tls.certificates.KeyType
import java.security.KeyStore

internal const val TEST_TLS_PASSWORD = "teamtalk-test-tls"

internal fun createTestPkcs12(): KeyStore {
    val password = TEST_TLS_PASSWORD.toCharArray()
    val generated = generateCertificate(
        algorithm = "SHA256withRSA",
        keyAlias = "teamtalk-test",
        keyPassword = TEST_TLS_PASSWORD,
        jksPassword = TEST_TLS_PASSWORD,
        keySizeInBits = 2048,
    )
    return KeyStore.getInstance("PKCS12").apply {
        load(null, password)
        setKeyEntry(
            "teamtalk-test",
            generated.getKey("teamtalk-test", password),
            password,
            generated.getCertificateChain("teamtalk-test"),
        )
    }
}

internal fun createTestServerTlsMaterial(): ServerTlsMaterial {
    val password = TEST_TLS_PASSWORD.toCharArray()
    return try {
        ServerTlsMaterial.create(createTestPkcs12(), password, password)
    } finally {
        password.fill('\u0000')
    }
}

/** 由一个 CA 签发的两个不同叶子证书；若固定（pin）的是证书链而非叶子证书，则两个证书都会被信任。 */
internal fun createTestServerTlsMaterialsWithSharedCa(): Pair<ServerTlsMaterial, ServerTlsMaterial> {
    val ca = generateCertificate(
        algorithm = "SHA256withRSA",
        keyAlias = "teamtalk-test-ca",
        keyPassword = TEST_TLS_PASSWORD,
        jksPassword = TEST_TLS_PASSWORD,
        keySizeInBits = 2048,
        keyType = KeyType.CA,
    )
    fun signedLeaf(alias: String): ServerTlsMaterial {
        val leaf = ca.generateCertificate(
            algorithm = "SHA256withRSA",
            keyAlias = alias,
            keyPassword = TEST_TLS_PASSWORD,
            jksPassword = TEST_TLS_PASSWORD,
            keySizeInBits = 2048,
            caKeyAlias = "teamtalk-test-ca",
            caPassword = TEST_TLS_PASSWORD,
            keyType = KeyType.Server,
        )
        val password = TEST_TLS_PASSWORD.toCharArray()
        return try {
            val pkcs12 = KeyStore.getInstance("PKCS12").apply {
                load(null, password)
                setKeyEntry(alias, leaf.getKey(alias, password), password, leaf.getCertificateChain(alias))
            }
            ServerTlsMaterial.create(pkcs12, password, password)
        } finally {
            password.fill('\u0000')
        }
    }
    return signedLeaf("teamtalk-leaf-a") to signedLeaf("teamtalk-leaf-b")
}
