package com.virjar.tk.rpc.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

@OptIn(ExperimentalCompilerApi::class)
class RpcProcessorCompileTest {

    @Test
    fun `invalid IDL fails processing with actionable diagnostics`() {
        val result = compile(
            RPC_ANNOTATIONS,
            SourceFile.kotlin(
                "FakeProto.kt",
                """
                package com.virjar.tk.model

                class LooksLikeProto
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "InvalidRpc.kt",
                """
                package fixture

                import com.virjar.tk.model.LooksLikeProto
                import com.virjar.tk.rpc.RpcMethod
                import com.virjar.tk.rpc.RpcService

                @RpcService("missing")
                interface MissingIdRpc {
                    suspend fun call()
                }

                @RpcService("duplicate")
                interface DuplicateIdRpc {
                    @RpcMethod(1)
                    suspend fun first()

                    @RpcMethod(1)
                    suspend fun second()
                }

                @RpcService("nullable")
                interface NullableReturnRpc {
                    @RpcMethod(1)
                    suspend fun value(): String?
                }

                @RpcService("fakeProto")
                interface FakeProtoListRpc {
                    @RpcMethod(1)
                    suspend fun values(): List<LooksLikeProto>
                }
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "必须显式声明 @RpcMethod(id): call")
        assertContains(result.messages, "methodId=1 重复分配")
        assertContains(result.messages, "RPC 返回类型禁止 nullable")
        assertContains(result.messages, "List 返回的元素类型仅支持 String/IProto 实现")
    }

    @Test
    fun `same simple annotation names outside protocol package are ignored`() {
        val result = compile(
            SourceFile.kotlin(
                "UnrelatedAnnotations.kt",
                """
                package fixture

                annotation class RpcService(val name: String)
                annotation class RpcMethod(val id: Int)

                @RpcService("not-rpc")
                interface NotAnRpcService {
                    @RpcMethod(1)
                    suspend fun call()
                }
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    private fun compile(vararg sources: SourceFile): JvmCompilationResult =
        KotlinCompilation().apply {
            inheritClassPath = true
            useKsp2()
            this.sources = sources.toList()
            symbolProcessorProviders += RpcProcessorProvider()
            verbose = false
        }.compile()

    private companion object {
        val RPC_ANNOTATIONS = SourceFile.kotlin(
            "RpcAnnotations.kt",
            """
            package com.virjar.tk.rpc

            @Target(AnnotationTarget.CLASS)
            @Retention(AnnotationRetention.SOURCE)
            annotation class RpcService(val name: String)

            @Target(AnnotationTarget.FUNCTION)
            @Retention(AnnotationRetention.SOURCE)
            annotation class RpcMethod(val id: Int)
            """.trimIndent(),
        )
    }
}
