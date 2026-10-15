@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.virjar.tk.shared.client

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Security.*
import kotlin.test.*

/**
 * 回归：部署固定的是一张精确的自签叶子证书，但 Apple 基线策略拒绝有效期超过 825 天的
 * 服务器证书，自定义锚点也无豁免；TcpTlsCertificates 生成的是 3650 天证书，私有部署的
 * iOS 客户端因此从未通过握手。字节级 pinning 必须先于策略评测接受精确匹配的叶子，
 * 不匹配的展示继续走 SecTrust 评测并拒绝。
 */
class IosTcpPinnedTrustTest {
    // 与 TcpTlsCertificates.generateTcpTlsCertificate 同规格：RSA-2048 自签、CN/SAN 均为
    // 127.0.0.1、serverAuth EKU、digitalSignature+keyEncipherment、有效期 3650 天。
    private val longValidityPem = """
-----BEGIN CERTIFICATE-----
MIIDOzCCAiOgAwIBAgIUZSOPlU/2XE5pCAUG2+vYrP8PH1MwDQYJKoZIhvcNAQEL
BQAwFDESMBAGA1UEAwwJMTI3LjAuMC4xMB4XDTI2MTAwODE1MDMzOVoXDTM2MTAw
NTE1MDMzOVowFDESMBAGA1UEAwwJMTI3LjAuMC4xMIIBIjANBgkqhkiG9w0BAQEF
AAOCAQ8AMIIBCgKCAQEAoMC3e64eb1ANYD16Smv2BwkpTLtpqfEtLF6XoLWtzw9X
60H5htRY2i03q2evSQouENcYpdWiX6BPZkJU7EBwoKgb7iBq5icgXek2gTTAFVwa
oZJsPgH3VGONkrnFlV5RJj15pM8iNB8AWDePH/xCz8OTzAXdM51zyjEwNSJbHNo1
Jj5UbbMmv8mJm0rx13NkzlLfeSHGiQS4eoDtyR/z8uBbiySF3KY5tOqPDtiH50Hf
xz26iZFUvJ2Z3H/oLD2upA5RtZW7QE5uhjWWwNEYzRNLI1reEkwXiZ/ZStmmernE
rcyESImfd26W/Jem3wwAUYex0GnIakwjuyHN6HFpDwIDAQABo4GEMIGBMB0GA1Ud
DgQWBBT3ybqf2gdka4DLlVscZWIDZGKz1DAfBgNVHSMEGDAWgBT3ybqf2gdka4DL
lVscZWIDZGKz1DAPBgNVHREECDAGhwR/AAABMBMGA1UdJQQMMAoGCCsGAQUFBwMB
MAsGA1UdDwQEAwIFoDAMBgNVHRMBAf8EAjAAMA0GCSqGSIb3DQEBCwUAA4IBAQAb
qoPEZMvy6io8St2yd78DiOMBJ7YalKyXFuk0mcrTE7qIfcy/oxP6BmgVqrbjz7+8
5FqXbnuhYBgka8XDAKxAjqe8COjOGBHwSAGbaX/JkwVwCnmWibNW9xF2Qir0uwJA
ACOpVRRIsIhquWdddpp40yw23T8qe4Ls6jmfN+/X7MU6LPp6OQfULJ/WuFUlmo8u
bFL1F5x6LEJtxt1FVmEHURIOTIyciCeqdPVKiLcr83ACoPoTGr4/EKPMX6IQOF/p
6yEYhJle3k7tJ+vM2LB4Zm+GUIGBzV3hSY5LpPdoMk3ZkEcSLxwf4UERwXEIF+1T
IY5C0y9qzj/lESr8TAP1
-----END CERTIFICATE-----
    """.trimIndent()

    private val differentLeafPem = """
-----BEGIN CERTIFICATE-----
MIIDOzCCAiOgAwIBAgIUYlIZIaWSR1PEXcVZPaiiuHt0YwkwDQYJKoZIhvcNAQEL
BQAwFDESMBAGA1UEAwwJMTI3LjAuMC4xMB4XDTI2MTAwODE1MDQzMloXDTM2MTAw
NTE1MDQzMlowFDESMBAGA1UEAwwJMTI3LjAuMC4xMIIBIjANBgkqhkiG9w0BAQEF
AAOCAQ8AMIIBCgKCAQEA2nAw7CMuLr8zGC+UI7MiaqHsh52uw2iN24HG9GgL4gcQ
xDj1xzfzsKU16EfXvuqc9g45oJq7U3YC9rjwrYdclL8hnyVUlyp5H1fp7NAWCnO6
xkhJCNlXx5YTzlE4tWLBMloTU1YmuJc5kH2K25YUXA9tKR+YZ23xddDjxnJDijfO
V7dhL99lWlmL9fxunNyKCQEWiT179R622FKVsYIPsMyCKrcvqqhf2F+V0W0QmVdt
zVGr9peBqzwKSeU5t4T8urZIVYdj7R7p2wYpb2jE69BBbjiSRCDWfMcw+BllsmTa
mCazLiJR480MFwgKZvUYRbd8SJ0Cmw9GE7nsOZWdTQIDAQABo4GEMIGBMB0GA1Ud
DgQWBBQMkn3/8iIlPHvZ58PMApGHcoQiATAfBgNVHSMEGDAWgBQMkn3/8iIlPHvZ
58PMApGHcoQiATAPBgNVHREECDAGhwR/AAABMBMGA1UdJQQMMAoGCCsGAQUFBwMB
MAsGA1UdDwQEAwIFoDAMBgNVHRMBAf8EAjAAMA0GCSqGSIb3DQEBCwUAA4IBAQA9
PdPic3T+ky24seGHEbQgzvszUaFsNMQydAiEqtygV7tmSzjaONTJp/M/0uWyIJ4U
MuCUrTUNROjWsubPi2JyyKzsZxbCBbrl7P1IA5QHn3bQTbMdBuFH0NbA5/NjGU/Y
HhsvtdRk4b2wRvVOondqIg+2Jfi3YtXDJ9ieUTM9o7zYnJUqCQW+h/Enjv8vjIZk
YNW8vI9T+MQWVHb3R59rKCrhj1RlyS2Wy6gSFYtK2/hh/8JVlmOPx97nhVhwY4Q5
AvDUeWtacyeSbVNgdIojnGr9gvqdNmmwgVrUkp7fsID9dJh3yc8DUrS0SOuYvZq0
hfq1QrDRsfOIxmhknoFx
-----END CERTIFICATE-----
    """.trimIndent()

    @Test
    fun exactLongValidityLeafMatchesItsPin() = memScoped {
        val pinned = pemCertificate(longValidityPem)
        val presented = pinned.usePinned { pinnedAddress ->
            CFDataCreate(kCFAllocatorDefault, pinnedAddress.addressOf(0).reinterpret(), pinned.size.toLong())
        }
        try {
            assertTrue(pinnedLeafMatches(presented, pinned), "The generator's 3650-day leaf must settle the pin")
            assertFalse(pinnedLeafMatches(presented, pemCertificate(differentLeafPem)))
            assertFalse(pinnedLeafMatches(null, pinned))
        } finally {
            presented?.let(::CFRelease)
        }
    }
}
