package com.virjar.tk.api

import com.virjar.tk.domain.auth.AccessTokenValidator
import com.virjar.tk.domain.auth.TokenInfo

internal class TestAccessTokenValidator(
    private val tokens: Map<String, TokenInfo> = emptyMap(),
) : AccessTokenValidator {
    override suspend fun validateAccessToken(token: String): TokenInfo? = tokens[token]

    companion object {
        fun single(
            token: String,
            uid: String,
            deviceId: String = "test-device",
            deviceFlag: Int = 0,
        ): TestAccessTokenValidator = TestAccessTokenValidator(
            mapOf(
                token to TokenInfo(
                    uid = uid,
                    deviceId = deviceId,
                    deviceFlag = deviceFlag,
                    createdAt = 1,
                    expiresAt = Long.MAX_VALUE,
                    userCredentialEpoch = 1,
                    deviceCredentialEpoch = 1,
                ),
            ),
        )
    }
}
