package com.virjar.tk.app.client

/** 在任何持久 owner 被退役之前，校验显式账号替换的结果。 */
internal sealed interface AuthReplacementPreflight {
    data class Accepted(val cleanupFailure: Throwable?) : AuthReplacementPreflight
    data class Rejected(val reason: String) : AuthReplacementPreflight
    data object StalePresentation : AuthReplacementPreflight
}

/**
 * 把本地可判定的输入失败保持在账号替换的非破坏性一侧。
 * [beginReplacement] 刻意放在校验 catch 之外：生命周期失败绝不能误标为表单错误，
 * 而无效输入绝不能调用破坏性回调。
 */
internal fun preflightAuthReplacement(
    presentationSubmission: AuthenticationPresentationSubmission,
    invalidFallback: String,
    validate: () -> Unit,
    beginReplacement: () -> Throwable?,
): AuthReplacementPreflight {
    return when (val admission = presentationSubmission.owner.validateAndAdmit(
        submission = presentationSubmission,
        invalidFallback = invalidFallback,
        validate = validate,
    )) {
        AuthenticationPresentationSubmissionAdmission.Admitted ->
            AuthReplacementPreflight.Accepted(beginReplacement())
        is AuthenticationPresentationSubmissionAdmission.Invalid ->
            AuthReplacementPreflight.Rejected(admission.reason)
        AuthenticationPresentationSubmissionAdmission.Stale ->
            AuthReplacementPreflight.StalePresentation
    }
}
