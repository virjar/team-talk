package com.virjar.tk.server.protocol

import com.virjar.tk.protocol.ProtocolRange
import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.server.infra.health.ServerBuildIdentity

/** One startup policy shared by every connection; operators may only tighten the build's window. */
data class ServerProtocolConfiguration(
    val supported: ProtocolRange = ProtocolVersions.SUPPORTED,
    val serverReleaseVersion: String = ServerBuildIdentity.current.version,
) {
    companion object {
        const val MINIMUM_MINOR_ENV = "MINIMUM_PROTOCOL_MINOR"

        fun fromEnvironment(environment: Map<String, String>): ServerProtocolConfiguration {
            val raw = environment[MINIMUM_MINOR_ENV]
                ?: return ServerProtocolConfiguration()
            require(raw.matches(Regex("0|[1-9][0-9]{0,4}"))) {
                "$MINIMUM_MINOR_ENV must be a canonical non-negative minor version"
            }
            val minimum = raw.toInt()
            require(minimum in ProtocolVersions.MINIMUM_MINOR..ProtocolVersions.MINOR) {
                "$MINIMUM_MINOR_ENV must be in ${ProtocolVersions.MINIMUM_MINOR}..${ProtocolVersions.MINOR}"
            }
            return ServerProtocolConfiguration(
                ProtocolRange(ProtocolVersions.MAJOR, minimum, ProtocolVersions.MINOR),
            )
        }
    }
}
