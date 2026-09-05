package com.virjar.tk.app.client
import com.virjar.tk.shared.client.ClientSession

/** 校验并原子地消费一个已渲染认证界面的结果。 */
internal sealed interface AuthenticationPresentationSubmissionAdmission {
    data object Admitted : AuthenticationPresentationSubmissionAdmission

    data class Invalid(val reason: String) : AuthenticationPresentationSubmissionAdmission

    data object Stale : AuthenticationPresentationSubmissionAdmission
}

/**
 * 把已渲染的 login/register 回调与认证尝试和工作区线性化。
 *
 * 每一次 [captureSubmission] 都携带当前的呈现 generation。第一个有效动作在任何账号替换
 * 开始之前消费该 generation。发布工作区、返回登录或退役控制器都会推进 generation，
 * 因此旧 Compose/IME 界面保留的回调绝不可能再次变得有效。
 */
internal class AuthenticationPresentationAdmission(
    initiallyShowingLogin: Boolean,
) {
    private enum class Phase { OPEN, IN_FLIGHT, CLOSED }

    private val lock = Any()
    private var generation = 1L
    private var phase = if (initiallyShowingLogin) Phase.OPEN else Phase.CLOSED

    fun captureSubmission(): AuthenticationPresentationSubmission = synchronized(lock) {
        AuthenticationPresentationSubmission(this, generation)
    }

    fun publishWorkspace() = advanceTo(Phase.CLOSED)

    fun showLogin() = advanceTo(Phase.OPEN)

    fun retire() = advanceTo(Phase.CLOSED)

    /** 只重新打开当前显式尝试；可重试的 refresh 失败没有这样的阶段。 */
    fun reopenInFlightAttempt() = synchronized(lock) {
        if (phase == Phase.IN_FLIGHT) advanceToLocked(Phase.OPEN)
    }

    internal fun validateAndAdmit(
        submission: AuthenticationPresentationSubmission,
        invalidFallback: String,
        validate: () -> Unit,
    ): AuthenticationPresentationSubmissionAdmission = synchronized(lock) {
        if (submission.owner !== this || submission.generation != generation || phase != Phase.OPEN) {
            return@synchronized AuthenticationPresentationSubmissionAdmission.Stale
        }
        try {
            validate()
        } catch (failure: IllegalArgumentException) {
            return@synchronized AuthenticationPresentationSubmissionAdmission.Invalid(
                failure.message ?: invalidFallback,
            )
        }
        phase = Phase.IN_FLIGHT
        AuthenticationPresentationSubmissionAdmission.Admitted
    }

    internal fun reopenAfterStartFailure(submission: AuthenticationPresentationSubmission) =
        synchronized(lock) {
            if (
                submission.owner === this &&
                submission.generation == generation &&
                phase == Phase.IN_FLIGHT
            ) {
                advanceToLocked(Phase.OPEN)
            }
        }

    private fun advanceTo(next: Phase) = synchronized(lock) { advanceToLocked(next) }

    private fun advanceToLocked(next: Phase) {
        check(generation < Long.MAX_VALUE) {
            "Authentication presentation generation exhausted"
        }
        generation += 1L
        phase = next
    }
}

/** 恰好由一个已渲染认证状态捕获的不透明能力。 */
internal class AuthenticationPresentationSubmission internal constructor(
    internal val owner: AuthenticationPresentationAdmission,
    internal val generation: Long,
) {
    fun reopenAfterStartFailure() = owner.reopenAfterStartFailure(this)
}

/**
 * 在平台接收到它的不可变 AuthState 之前，证明控制器的发布不变量。
 */
internal fun <Session : Any> requirePublishedWorkspace(
    resourceSession: Session?,
    publishedWorkspace: Session?,
    isActive: (Session) -> Boolean,
): Session? {
    check(publishedWorkspace == null || publishedWorkspace === resourceSession) {
        "Published authentication workspace must own the controller's exact session"
    }
    check(publishedWorkspace == null || isActive(publishedWorkspace)) {
        "Published authentication workspace session must be active"
    }
    return publishedWorkspace
}

internal fun publishAuthenticationWorkspace(
    presentation: AuthenticationPresentationAdmission,
    resourceSession: ClientSession?,
    activeSession: ClientSession,
): ClientSession {
    val published = checkNotNull(
        requirePublishedWorkspace(resourceSession, activeSession, ClientSession::isBusinessActive),
    )
    presentation.publishWorkspace()
    return published
}
