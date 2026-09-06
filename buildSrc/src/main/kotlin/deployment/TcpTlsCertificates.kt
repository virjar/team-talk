package deployment

import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

internal fun readTcpTlsCertificate(pem: String): X509Certificate =
    CertificateFactory.getInstance("X.509")
        .generateCertificate(pem.byteInputStream(Charsets.UTF_8)) as X509Certificate

private fun isIpv4(host: String): Boolean = host.split('.').let { parts ->
    parts.size == 4 && parts.all { part ->
        part.matches(Regex("0|[1-9][0-9]{0,2}")) && part.toInt() in 0..255
    }
}

private fun requireCertificateHostSyntax(host: String) {
    require(host.length <= 253 && (isIpv4(host) ||
        (!host.matches(Regex("[0-9.]+")) && host.split('.').all {
            it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))
        }))) { "TCP certificate host must be a canonical IPv4 address or DNS hostname" }
}

/** 部署时提早发现目标地址错误；运行时仍由 JSSE 校验服务端证书和端点身份。 */
internal fun requireTcpCertificateHost(certificate: X509Certificate, host: String) {
    requireCertificateHostSyntax(host)
    certificate.checkValidity()
    val ip = isIpv4(host)
    val matches = certificate.subjectAlternativeNames.orEmpty().any { entry ->
        val value = entry.getOrNull(1) as? String ?: return@any false
        if (ip) entry.firstOrNull() == GeneralName.iPAddress && value == host
        else entry.firstOrNull() == GeneralName.dNSName && (
            value.equals(host, ignoreCase = true) ||
                (value.startsWith("*.") && host.substringAfter('.', "")
                    .equals(value.removePrefix("*."), ignoreCase = true))
            )
    }
    require(matches) { "TCP TLS certificate subjectAltName does not match $host" }
    val usage = certificate.extendedKeyUsage
    require(usage == null || KeyPurposeId.id_kp_serverAuth.id in usage || "2.5.29.37.0" in usage) {
        "TCP TLS certificate does not permit server authentication"
    }
}

private fun validateExistingPair(directory: File, host: String) {
    val certificateFile = File(directory, "certificate.pem")
    val keyFile = File(directory, "private-key.pem")
    require(certificateFile.isFile && keyFile.isFile) {
        "TCP TLS directory must contain both certificate.pem and private-key.pem; existing material was not changed"
    }
    val certificate = readTcpTlsCertificate(certificateFile.readText())
    requireTcpCertificateHost(certificate, host)
    val privateKey = PEMParser(keyFile.reader()).use { parser ->
        val converter = JcaPEMKeyConverter()
        when (val value = parser.readObject()) {
            is PrivateKeyInfo -> converter.getPrivateKey(value)
            is PEMKeyPair -> converter.getKeyPair(value).private
            else -> error("TCP TLS private key must be an unencrypted PEM private key")
        }
    }
    val algorithm = when (privateKey.algorithm) {
        "RSA" -> "SHA256withRSA"
        "EC", "ECDSA" -> "SHA256withECDSA"
        else -> error("Unsupported TCP TLS private key algorithm: ${privateKey.algorithm}")
    }
    val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
    val signature = Signature.getInstance(algorithm).run {
        initSign(privateKey)
        update(challenge)
        sign()
    }
    require(Signature.getInstance(algorithm).run {
        initVerify(certificate.publicKey)
        update(challenge)
        verify(signature)
    }) { "TCP TLS private key does not match certificate.pem; existing material was not changed" }
}

/**
 * 一次生成，随后只验证并复用。该目录独立于 build/，clean 或普通升级不能触发换证。
 * 先在兄弟临时目录完成整对材料，再移动发布；绝不覆盖已有证书或私钥。
 */
fun generateTcpTlsCertificate(directory: File, host: String) {
    requireCertificateHostSyntax(host)
    if (directory.exists()) {
        validateExistingPair(directory, host)
        return
    }
    val parent = directory.absoluteFile.parentFile.apply { mkdirs() }
    val temporary = Files.createTempDirectory(parent.toPath(), ".tcp-tls-").toFile()
    try {
        if (Files.getFileStore(temporary.toPath()).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(temporary.toPath(), PosixFilePermissions.fromString("rwx------"))
        }
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val subject = X500Name("CN=$host")
        val now = Instant.now()
        val builder = JcaX509v3CertificateBuilder(
            subject, BigInteger(159, SecureRandom()).add(BigInteger.ONE),
            Date.from(now.minus(5, ChronoUnit.MINUTES)), Date.from(now.plus(3650, ChronoUnit.DAYS)),
            subject, keys.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(
            GeneralName(if (isIpv4(host)) GeneralName.iPAddress else GeneralName.dNSName, host),
        ))
        val certificate = JcaX509CertificateConverter().getCertificate(
            builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private)),
        )
        val keyFile = File(temporary, "private-key.pem").apply { createNewFile() }
        setOwnerOnly(keyFile.toPath())
        JcaPEMWriter(keyFile.writer()).use { it.writeObject(keys.private) }
        JcaPEMWriter(File(temporary, "certificate.pem").writer()).use { it.writeObject(certificate) }
        validateExistingPair(temporary, host)
        Files.move(temporary.toPath(), directory.toPath())
    } finally {
        temporary.deleteRecursively()
    }
}
