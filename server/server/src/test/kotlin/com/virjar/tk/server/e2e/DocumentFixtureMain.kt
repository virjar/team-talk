package com.virjar.tk.server.e2e

import kotlinx.coroutines.runBlocking

/**
 * 可复用 Desktop/Android 文档测试夹具的测试运行时入口。
 *
 * 不接受通过 args、Gradle 属性或 JVM 属性传入的凭据。唯一允许的秘密来源是
 * 既有私有状态目录内的 `account.properties`。其余输入都是由
 * tools/e2e/document_fixture.py 提供的非秘密操作围栏。
 */
fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--help"))) {
        println(documentFixtureHelp())
        return
    }
    require(args.isEmpty()) {
        "documentFixture accepts no command-line values; use the documented non-secret environment fences"
    }

    loadDocumentFixtureInvocationFromProcess().use { invocation ->
        var manifest = invocation.files.loadOrCreateManifest(invocation.target)
        val plan = validateDocumentFixtureManifest(manifest, invocation.target)

        println(
            "[DocumentFixture] target=${invocation.target} action=${invocation.action.wireName} " +
                "fixtureId=${manifest.fixtureId} documents=${plan.nodes.size}",
        )
        manifest = runBlocking {
            when (invocation.action) {
                DocumentFixtureAction.SEED -> seedDocumentFixture(invocation, manifest, plan)
                DocumentFixtureAction.ARCHIVE -> archiveDocumentFixture(invocation, manifest, plan)
            }
        }
        println(
            "[DocumentFixture] status=${manifest.status} spaceId=${manifest.spaceId} " +
                "documents=${manifest.createdDocuments} manifest=${invocation.files.manifestPath}",
        )
    }
}

internal fun documentFixtureHelp(): String = """
    TeamTalk document UI fixture

    Invoke this main through :server:documentFixture and tools/e2e/document_fixture.py.
    Secrets are read only from a private account.properties file; command-line credentials and
    JVM/Gradle credential properties are intentionally unsupported.
""".trimIndent()
