package com.virjar.tk.app.client

import com.virjar.tk.shared.client.ImClient
import com.virjar.tk.protocol.model.AuthRules

internal sealed interface AuthSubmissionResult {
    data object Accepted : AuthSubmissionResult
    data class Rejected(val reason: String) : AuthSubmissionResult
    data object StalePresentation : AuthSubmissionResult
}

/**
 * 拥有显式 login/register 替换的校验和传输提交。
 *
 * Compose 仍然负责展示结果，而这个协作者把安全敏感的排序集中在一处：
 * 先校验，退役上一个 owner，把已接受的清理状态发布到 UI，然后才把新的 AUTH 尝试入队。
 */
internal class AuthSubmissionCoordinator(
    private val imClient: ImClient,
    private val credentialOwner: AuthControllerCredentialOwner,
    private val tcpHost: String,
    private val tcpPort: Int,
    private val deviceId: String,
    private val deviceName: String,
    private val deviceModel: String?,
    private val deviceFlag: Int,
) {
    fun submitLogin(
        presentationSubmission: AuthenticationPresentationSubmission,
        username: String,
        password: String,
        beginReplacement: () -> Throwable?,
        onReplacementAccepted: (cleanupFailure: Throwable?) -> Unit,
    ): AuthSubmissionResult = submit(
        presentationSubmission = presentationSubmission,
        invalidFallback = "登录信息不合法",
        validate = {
            AuthRules.validateLogin(username, password)
            validateDevice()
        },
        beginReplacement = beginReplacement,
        onReplacementAccepted = onReplacementAccepted,
        start = {
            imClient.login(
                username,
                password,
                deviceId,
                deviceName,
                tcpHost,
                tcpPort,
                deviceModel,
                deviceFlag,
            )
        },
    )

    fun submitRegistration(
        presentationSubmission: AuthenticationPresentationSubmission,
        username: String,
        password: String,
        name: String,
        beginReplacement: () -> Throwable?,
        onReplacementAccepted: (cleanupFailure: Throwable?) -> Unit,
    ): AuthSubmissionResult = submit(
        presentationSubmission = presentationSubmission,
        invalidFallback = "注册信息不合法",
        validate = {
            AuthRules.validateRegister(username, password, name)
            validateDevice()
        },
        beginReplacement = beginReplacement,
        onReplacementAccepted = onReplacementAccepted,
        start = {
            imClient.register(
                username,
                password,
                name,
                deviceId,
                deviceName,
                tcpHost,
                tcpPort,
                deviceModel,
                deviceFlag,
            )
        },
    )

    private fun submit(
        presentationSubmission: AuthenticationPresentationSubmission,
        invalidFallback: String,
        validate: () -> Unit,
        beginReplacement: () -> Throwable?,
        onReplacementAccepted: (cleanupFailure: Throwable?) -> Unit,
        start: () -> Unit,
    ): AuthSubmissionResult {
        return when (val preflight = preflightAuthReplacement(
            presentationSubmission = presentationSubmission,
            invalidFallback = invalidFallback,
            validate = validate,
            beginReplacement = beginReplacement,
        )) {
            AuthReplacementPreflight.StalePresentation ->
                AuthSubmissionResult.StalePresentation
            is AuthReplacementPreflight.Rejected -> {
                // 无效的替换输入绝不能清除持久账号。停止任何更早的、
                // 可能仍在后台运行的一次性密码尝试。
                imClient.disconnect()
                AuthSubmissionResult.Rejected(preflight.reason)
            }
            is AuthReplacementPreflight.Accepted -> {
                // 这个回调必须先于 start()：在本地传输上 AUTH 可能立即完成，
                // 而 UI 不得在该边界之后发布过期的尝试反馈。
                onReplacementAccepted(preflight.cleanupFailure)
                try {
                    start()
                    AuthSubmissionResult.Accepted
                } catch (failure: IllegalArgumentException) {
                    credentialOwner.retireAuthResults()
                    presentationSubmission.reopenAfterStartFailure()
                    AuthSubmissionResult.Rejected(failure.message ?: invalidFallback)
                }
            }
        }
    }

    private fun validateDevice() {
        AuthRules.validateDevice(deviceId, deviceName, deviceModel, deviceFlag)
    }
}

/** 一个不可变 AuthState 呈现所导出的 generation 绑定回调。 */
internal class AuthSubmissionActions(
    private val coordinator: AuthSubmissionCoordinator,
    private val presentationSubmission: AuthenticationPresentationSubmission,
    private val canSubmit: () -> Boolean,
    private val beginReplacement: () -> Throwable?,
    private val onReplacementAccepted: (Throwable?) -> Unit,
    private val publishError: (String) -> Unit,
) {
    fun login(username: String, password: String): AuthSubmissionDisposition {
        if (!canSubmit()) return AuthSubmissionDisposition.STALE
        return coordinator.submitLogin(
            presentationSubmission = presentationSubmission,
            username = username,
            password = password,
            beginReplacement = beginReplacement,
            onReplacementAccepted = onReplacementAccepted,
        ).toDisposition()
    }

    fun register(username: String, password: String, name: String): AuthSubmissionDisposition {
        if (!canSubmit()) return AuthSubmissionDisposition.STALE
        return coordinator.submitRegistration(
            presentationSubmission = presentationSubmission,
            username = username,
            password = password,
            name = name,
            beginReplacement = beginReplacement,
            onReplacementAccepted = onReplacementAccepted,
        ).toDisposition()
    }

    private fun AuthSubmissionResult.toDisposition(): AuthSubmissionDisposition = when (this) {
        AuthSubmissionResult.Accepted -> AuthSubmissionDisposition.ACCEPTED
        is AuthSubmissionResult.Rejected -> {
            publishError(reason)
            AuthSubmissionDisposition.REJECTED
        }
        AuthSubmissionResult.StalePresentation -> AuthSubmissionDisposition.STALE
    }
}
