package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.auth.AuthenticatedDevicePolicy
import com.virjar.tk.server.domain.auth.AuthService
import com.virjar.tk.server.domain.telemetry.TelemetryCollectionMode
import com.virjar.tk.server.domain.telemetry.TelemetryDeviceAuthority
import com.virjar.tk.server.domain.telemetry.TelemetryRuntimeSnapshot
import com.virjar.tk.server.infra.db.Credentials
import com.virjar.tk.server.infra.db.Devices
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env
    private val authConnectionGeneration = AtomicLong()

    @Test
    fun `retired credential generation cannot retain exact diagnostics or resurrect its profile`() = runTest {
        val username = uniqueUsername("telemetry-device-generation")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        val deviceId = "diagnostic-relogin"
        val firstLogin = login(username, password, deviceId, "First", "Model", 1)
        val firstPrincipal = assertNotNull(
            ctx.accessTokenValidator.validateAccessToken(assertNotNull(firstLogin.accessToken)),
        )
        val firstAuthority = firstPrincipal.toTelemetryAuthority()
        val observedAt = System.currentTimeMillis()
        assertTrue(
            ctx.clientTelemetryControl.refreshDevice(
                firstAuthority,
                telemetryRuntime("1.0.0"),
                observedAt,
                acceptedEventAt = null,
            ),
        )
        ctx.clientTelemetryControl.enableDiagnosticPolicy(
            uid = uid,
            deviceId = deviceId,
            reason = "credential generation retirement test",
            expiresAt = observedAt + 60_000L,
            actor = "test",
            now = observedAt,
        )

        assertNotNull(
            ctx.tokenRepository.revokeDeviceIfCurrent(uid, deviceId, firstPrincipal.deviceCredentialEpoch),
        )
        assertNull(ctx.clientTelemetryControl.findDevice(uid, deviceId))
        assertEquals(
            TelemetryCollectionMode.BASELINE,
            ctx.clientTelemetryControl.effectivePolicy(uid, deviceId, observedAt + 1L).mode,
        )

        val secondLogin = login(username, password, deviceId, "Second", "Model", 1)
        val secondPrincipal = assertNotNull(
            ctx.accessTokenValidator.validateAccessToken(assertNotNull(secondLogin.accessToken)),
        )
        assertTrue(secondPrincipal.deviceCredentialEpoch > firstPrincipal.deviceCredentialEpoch)
        assertEquals(
            false,
            ctx.clientTelemetryControl.refreshDevice(
                firstAuthority,
                telemetryRuntime("1.0.0"),
                observedAt + 2L,
                acceptedEventAt = null,
            ),
        )
        assertNull(ctx.clientTelemetryControl.findDevice(uid, deviceId))
        assertEquals(
            TelemetryCollectionMode.BASELINE,
            ctx.clientTelemetryControl.effectivePolicy(uid, deviceId, observedAt + 2L).mode,
        )
    }

    @Test
    fun `authentication registers and lists devices`() = runTest {
        val username = uniqueUsername("device-list")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        login(username, password, "dev-1", "iPhone", "iPhone 15", 1)
        login(username, password, "dev-2", "Desktop", "MacBook", 2)

        val devices = ctx.deviceRepo.getDevices(uid)
        assertEquals(2, devices.size)
        assertTrue(devices.any { it.deviceId == "dev-1" && it.deviceName == "iPhone" })
        assertTrue(devices.any { it.deviceId == "dev-2" && it.deviceName == "Desktop" })
    }

    @Test
    fun `authenticating same device updates info`() = runTest {
        val username = uniqueUsername("device-update")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        login(username, password, "dev-same", "Old Name", "Model A", 1)
        login(username, password, "dev-same", "New Name", "Model B", 1)

        val devices = ctx.deviceRepo.getDevices(uid)
        assertEquals(1, devices.size)
        assertEquals("New Name", devices[0].deviceName)
        assertEquals("Model B", devices[0].deviceModel)
    }

    @Test
    fun `revoking device removes it from active list`() = runTest {
        val username = uniqueUsername("device-revoke")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        login(username, password, "dev-kick", "Phone", "Pixel", 1)
        assertEquals(1, ctx.deviceRepo.getDevices(uid).size)

        assertTrue(ctx.authService.revokeDevice(uid, "dev-kick") != null)
        assertEquals(0, ctx.deviceRepo.getDevices(uid).size)
    }

    @Test
    fun `devices sorted by last login desc`() = runTest {
        val username = uniqueUsername("device-order")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        login(username, password, "dev-old", "Old", "Model", 1)
        Thread.sleep(10)
        login(username, password, "dev-new", "New", "Model", 1)

        val devices = ctx.deviceRepo.getDevices(uid)
        assertEquals(2, devices.size)
        assertEquals("dev-new", devices[0].deviceId)
    }

    @Test
    fun `successful authentication registers and refreshes the declared device`() = runTest {
        val username = uniqueUsername("device-auth")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)

        val login = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 0,
                username = username,
                password = password,
                deviceId = "android-install-1",
                deviceName = "Xiaomi Phone",
                deviceModel = "2312DRA50C",
                deviceFlag = 1,
                correlationId = "device-auth-login-0001",
                connectionGeneration = 1L,
            ),
        )
        assertEquals(0, login.code)
        assertEquals(uid, login.uid)
        assertEquals(
            listOf("android-install-1"),
            ctx.deviceRepo.getDevices(uid).map { it.deviceId },
        )

        val refresh = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 2,
                refreshToken = login.refreshToken,
                deviceId = "android-install-1",
                deviceName = "Xiaomi Phone (renamed)",
                deviceModel = "2312DRA50C",
                deviceFlag = 1,
                correlationId = "device-auth-refresh-0002",
                connectionGeneration = 2L,
            ),
        )
        assertEquals(0, refresh.code)
        val devices = ctx.deviceRepo.getDevices(uid)
        assertEquals(1, devices.size)
        assertEquals("Xiaomi Phone (renamed)", devices.single().deviceName)

        ctx.authService.revokeDevice(uid, "android-install-1")
        assertTrue(ctx.deviceRepo.getDevices(uid).isEmpty())
    }

    @Test
    fun `refresh cannot relabel another device`() = runTest {
        val username = uniqueUsername("device-binding")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        val login = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 0,
                username = username,
                password = password,
                deviceId = "trusted-device",
                deviceName = "Trusted phone",
                deviceFlag = 1,
                correlationId = "device-binding-auth-0001",
                connectionGeneration = 1L,
            ),
        )

        val forgedRefresh = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 2,
                refreshToken = login.refreshToken,
                deviceId = "forged-device",
                deviceName = "Forged phone",
                deviceFlag = 1,
                correlationId = "device-binding-auth-0002",
                connectionGeneration = 2L,
            ),
        )
        assertEquals(1, forgedRefresh.code)
        assertEquals(listOf("trusted-device"), ctx.deviceRepo.getDevices(uid).map { it.deviceId })

        // 不匹配的尝试绝不能改动真实设备的稳定 refresh 聚合。
        val validRefresh = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 2,
                refreshToken = login.refreshToken,
                deviceId = "trusted-device",
                deviceName = "Trusted phone",
                deviceFlag = 1,
                correlationId = "device-binding-auth-0003",
                connectionGeneration = 3L,
            ),
        )
        assertEquals(0, validRefresh.code)

        assertEquals(listOf("trusted-device"), ctx.deviceRepo.getDevices(uid).map { it.deviceId })
    }

    @Test
    fun `all-active device capacity fails closed only after valid credential proof`() = runTest {
        val username = uniqueUsername("device-cap")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        repeat(AuthenticatedDevicePolicy.MAX_DEVICES_PER_USER) { index ->
            login(username, password, "cap-device-$index", "Device $index", "Model", 1)
        }

        val overLimit = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 0,
                username = username,
                password = password,
                deviceId = "cap-overflow",
                correlationId = "device-capacity-auth-0001",
                connectionGeneration = 1L,
            ),
        )
        assertEquals(AuthResponsePayload.CODE_TOO_MANY_CONNECTIONS, overLimit.code)
        assertEquals(AuthService.DEVICE_LIMIT_RESPONSE_REASON, overLimit.reason)
        assertEquals(null, overLimit.uid)
        assertEquals(null, overLimit.accessToken)
        assertEquals(null, overLimit.refreshToken)

        val wrongPassword = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 0,
                username = username,
                password = "wrong-password",
                deviceId = "cap-overflow",
                correlationId = "device-capacity-auth-0002",
                connectionGeneration = 2L,
            ),
        )
        val missingUser = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 0,
                username = uniqueUsername("missing-device-cap"),
                password = "wrong-password",
                deviceId = "cap-overflow",
                correlationId = "device-capacity-auth-0003",
                connectionGeneration = 3L,
            ),
        )
        assertEquals(AuthResponsePayload.CODE_AUTH_FAILED, wrongPassword.code)
        assertEquals(wrongPassword.reason, missingUser.reason)
        assertEquals(AuthenticatedDevicePolicy.MAX_DEVICES_PER_USER, ctx.deviceRepo.getDevices(uid).size)
    }

    @Test
    fun `revoked slot is deterministically recycled without device epoch regression`() = runTest {
        val username = uniqueUsername("device-recycle")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        val deviceLogins = (0 until AuthenticatedDevicePolicy.MAX_DEVICES_PER_USER).map { index ->
            login(username, password, "recycle-device-$index", "Device $index", "Model", 1)
        }
        val retiredPrincipal = assertNotNull(
            ctx.accessTokenValidator.validateAccessToken(assertNotNull(deviceLogins.first().accessToken)),
        )
        val retiredAuthority = retiredPrincipal.toTelemetryAuthority()
        val telemetryObservedAt = System.currentTimeMillis()
        val retiredRuntime = telemetryRuntime("1.0.7")
        ctx.clientTelemetryControl.refreshDevice(
            retiredAuthority,
            retiredRuntime,
            telemetryObservedAt,
            acceptedEventAt = null,
        )
        ctx.clientTelemetryControl.enableDiagnosticPolicy(
            uid = uid,
            deviceId = "recycle-device-0",
            reason = "device retirement test",
            expiresAt = telemetryObservedAt + 60_000L,
            actor = "test",
            now = telemetryObservedAt,
        )

        val firstRevokedEpoch = assertNotNull(ctx.authService.revokeDevice(uid, "recycle-device-0"))
        ctx.authService.revokeDevice(uid, "recycle-device-1")
        login(username, password, "replacement-device", "Replacement", "Model", 1)
        val afterFirstRecycle = transaction(ctx.database) {
            Devices.selectAll().where { Devices.uid eq uid }.map { it[Devices.deviceId] }.toSet()
        }
        assertTrue("recycle-device-0" !in afterFirstRecycle)
        assertTrue("recycle-device-1" in afterFirstRecycle)
        assertEquals(null, ctx.clientTelemetryControl.findDevice(uid, "recycle-device-0"))
        assertTrue(
            ctx.clientTelemetryControl.pagePolicies(0L, 100).items.none {
                it.uid == uid && it.deviceId == "recycle-device-0"
            },
        )
        assertEquals(
            AuthenticatedDevicePolicy.MAX_DEVICES_PER_USER.toLong(),
            transaction(ctx.database) { Devices.selectAll().where { Devices.uid eq uid }.count() },
        )

        val returning = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 0,
                username = username,
                password = password,
                deviceId = "recycle-device-0",
                correlationId = "device-recycle-auth-0001",
                connectionGeneration = 1L,
            ),
        )
        assertEquals(0, returning.code)
        val principal = assertNotNull(
            ctx.accessTokenValidator.validateAccessToken(assertNotNull(returning.accessToken)),
        )
        assertTrue(principal.deviceCredentialEpoch > firstRevokedEpoch)
        val currentAuthority = principal.toTelemetryAuthority()
        val currentRuntime = retiredRuntime.copy(appVersion = "2.0.0")
        assertTrue(
            ctx.clientTelemetryControl.refreshDevice(
                currentAuthority,
                currentRuntime,
                telemetryObservedAt + 1L,
                acceptedEventAt = null,
            ),
        )
        assertEquals(
            false,
            ctx.clientTelemetryControl.refreshDevice(
                retiredAuthority,
                retiredRuntime,
                telemetryObservedAt + 2L,
                acceptedEventAt = null,
            ),
            "a delayed upload from the recycled credential generation must not mutate the new installation",
        )
        assertEquals(
            "2.0.0",
            assertNotNull(ctx.clientTelemetryControl.findDevice(uid, "recycle-device-0")).runtime.appVersion,
        )
        assertEquals(
            AuthenticatedDevicePolicy.MAX_DEVICES_PER_USER.toLong(),
            transaction(ctx.database) { Devices.selectAll().where { Devices.uid eq uid }.count() },
        )
        assertEquals(
            (AuthenticatedDevicePolicy.MAX_DEVICES_PER_USER * 2).toLong(),
            transaction(ctx.database) { Credentials.selectAll().where { Credentials.uid eq uid }.count() },
        )
    }

    @Test
    fun `concurrent new-device logins serialize at the persistent capacity`() = runTest {
        val username = uniqueUsername("device-cap-race")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        repeat(AuthenticatedDevicePolicy.MAX_DEVICES_PER_USER - 1) { index ->
            login(username, password, "race-existing-$index", "Existing $index", "Model", 1)
        }

        val responses = coroutineScope {
            (0 until 2).map { index ->
                async(Dispatchers.IO) {
                    ctx.authService.handleAuth(
                        AuthRequestPayload(
                            authType = 0,
                            username = username,
                            password = password,
                            deviceId = "race-device-$index",
                            correlationId = "device-race-auth-000$index",
                            connectionGeneration = index.toLong() + 1L,
                        ),
                    )
                }
            }.awaitAll()
        }

        assertEquals(1, responses.count { it.code == 0 })
        assertEquals(1, responses.count { it.code == AuthResponsePayload.CODE_TOO_MANY_CONNECTIONS })
        assertEquals(
            AuthenticatedDevicePolicy.MAX_DEVICES_PER_USER.toLong(),
            transaction(ctx.database) { Devices.selectAll().where { Devices.uid eq uid }.count() },
        )
    }

    private suspend fun login(
        username: String,
        password: String,
        deviceId: String,
        deviceName: String,
        deviceModel: String,
        deviceFlag: Int,
    ) = authConnectionGeneration.incrementAndGet().let { generation ->
        ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 0,
                username = username,
                password = password,
                deviceId = deviceId,
                deviceName = deviceName,
                deviceModel = deviceModel,
                deviceFlag = deviceFlag,
                correlationId = "device-helper-auth-$generation",
                connectionGeneration = generation,
            ),
        ).also { response -> assertEquals(0, response.code) }
    }

    private fun com.virjar.tk.server.domain.auth.TokenInfo.toTelemetryAuthority() = TelemetryDeviceAuthority(
        uid = uid,
        deviceId = deviceId,
        userCredentialEpoch = userCredentialEpoch,
        deviceCredentialEpoch = deviceCredentialEpoch,
    )

    private fun telemetryRuntime(appVersion: String) = TelemetryRuntimeSnapshot(
        platform = "ANDROID",
        osName = "Android",
        osVersion = "15",
        architecture = "arm64",
        deviceModel = "Test device",
        appVersion = appVersion,
        buildNumber = "1000007",
        gitCommit = "abcdef012345",
        buildIdentity = "$appVersion+${"a".repeat(40)}",
        buildTime = "2026-08-27 14:04",
        protocolVersion = 1,
        distribution = "android-debug",
    )
}
