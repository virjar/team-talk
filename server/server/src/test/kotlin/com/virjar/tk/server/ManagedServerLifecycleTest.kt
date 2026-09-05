package com.virjar.tk.server

import com.virjar.tk.server.runtime.ServerResourceOwner
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ManagedServerLifecycleTest {
    @Test
    fun `http bind failure retires resources acquired while configuring application`() {
        val loopback = InetAddress.getLoopbackAddress()
        val applicationPort = AtomicInteger()
        val configured = AtomicBoolean(false)
        val resources = ServerResourceOwner { _, _ -> }

        ServerSocket(0, 50, loopback).use { occupiedHttpPort ->
            val server = embeddedServer(
                factory = Netty,
                environment = applicationEnvironment {
                    log = LoggerFactory.getLogger("ManagedServerLifecycleTest")
                },
                configure = {
                    connector {
                        host = loopback.hostAddress
                        port = occupiedHttpPort.localPort
                    }
                },
            ) {
                val applicationSocket = ServerSocket(0, 50, loopback)
                applicationPort.set(applicationSocket.localPort)
                resources.own("application socket", applicationSocket) { it.close() }
                configured.set(true)
            }

            assertFails { startAndWaitForManagedServer(server, resources) }
        }

        assertTrue(configured.get(), "application resources must exist before the HTTP bind attempt")
        ServerSocket(applicationPort.get(), 50, loopback).use { rebound ->
            assertTrue(rebound.isBound, "failed HTTP startup must release application-owned ports")
        }
    }
}
