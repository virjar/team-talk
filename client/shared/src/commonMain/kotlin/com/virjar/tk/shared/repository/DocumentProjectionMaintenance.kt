package com.virjar.tk.shared.repository

import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentPolicyMutationResult
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import kotlinx.coroutines.CancellationException

/** 远程提交后的投影收敛策略与仅作诊断用的清理策略。 */
internal class DocumentProjectionMaintenance(
    private val localCache: LocalCache,
) {
    private val logger = PlatformOnlyTkLogger("DocumentRepository")

    fun requireConvergedDocument(remote: Document): Document = checkNotNull(
        localCache.getDocumentBody(remote.spaceId, remote.documentId)
            ?.takeIf { cached -> cached.revision >= remote.revision },
    ) { "document response was not retained by the local projection" }

    fun requireConvergedSpace(remote: DocumentSpace): DocumentSpace = checkNotNull(
        localCache.getDocumentSpaces().firstOrNull { cached ->
            cached.spaceId == remote.spaceId &&
                cached.updatedAt >= remote.updatedAt &&
                cached.custodyRevision >= remote.custodyRevision &&
                cached.policyRevision >= remote.policyRevision
        },
    ) { "document space response was not retained by the local projection" }

    fun validatePolicyMutationResult(
        requestedSpaceId: String,
        expectedPolicyRevision: Long,
        result: DocumentPolicyMutationResult,
    ) {
        check(
            result.spaceId == requestedSpaceId &&
                result.policyRevision >= expectedPolicyRevision &&
                result.effectiveRole in DocumentSpace.ROLE_NONE..DocumentSpace.ROLE_OWNER,
        ) { "document policy mutation response escaped its requested space" }
    }

    inline fun runPostCommit(diagnostic: String, cleanup: () -> Unit) =
        runPostCommit(diagnostic, Unit, cleanup)

    /** 远程命令已经提交；只有非致命的本地清理才属于诊断。 */
    inline fun <T> runPostCommit(diagnostic: String, fallback: T, action: () -> T): T {
        try {
            return action()
        } catch (failure: Throwable) {
            if (failure is CancellationException || failure !is Exception) throw failure
            try {
                logger.fault(diagnostic, failure)
            } catch (diagnosticFailure: Throwable) {
                if (diagnosticFailure is CancellationException || diagnosticFailure !is Exception) {
                    if (diagnosticFailure !== failure) diagnosticFailure.addSuppressed(failure)
                    throw diagnosticFailure
                }
            }
            return fallback
        }
    }
}
