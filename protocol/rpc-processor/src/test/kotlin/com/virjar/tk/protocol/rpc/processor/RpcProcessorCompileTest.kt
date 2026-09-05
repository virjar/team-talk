package com.virjar.tk.protocol.rpc.processor

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
    fun `last method can retire while the service retains its reserved ID`() {
        val result = compile(
            RPC_ANNOTATIONS,
            SourceFile.kotlin("ProtocolRuntime.kt", """
                package com.virjar.tk.protocol
                class ProtocolVersion
                class ProtocolAvailability {
                    fun supports(version: ProtocolVersion) = true
                }
                class PacketBuffer
                object ProtoCodec
            """.trimIndent()),
            SourceFile.kotlin("RpcRuntime.kt", """
                package com.virjar.tk.protocol.rpc
                interface RpcInvoker
                fun Any.ensureSuccess() = Unit
                class RpcProtocolUnavailableException : IllegalStateException()
                abstract class RpcStub(val uid: String) {
                    abstract suspend fun dispatch(methodId: Int, payload: ByteArray?): ByteArray
                }
            """.trimIndent()),
            SourceFile.kotlin("RetiredRpc.kt", """
                package fixture
                import com.virjar.tk.protocol.rpc.RpcService
                import com.virjar.tk.protocol.rpc.RpcReservedMethodIds
                @RpcService("retired")
                @RpcReservedMethodIds(1)
                interface RetiredRpc
            """.trimIndent()),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `string code enums are not mistaken for numeric binary wire identities`() {
        val result = compile(SourceFile.kotlin("JsonFaultCode.kt", """
            package fixture
            enum class JsonFaultCode(val code: String) {
                OPERATION_FAILED("operation_failed"),
                OPERATION_DENIED("operation_denied"),
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `protocol lifecycle annotations reject future introduction and expired implementations`() {
        val result = compile(
            RPC_ANNOTATIONS,
            SourceFile.kotlin("ProtocolLifecycle.kt", """
                package com.virjar.tk.protocol
                @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD)
                annotation class SinceProtocol(val minor: Int)
                @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD)
                annotation class RemovedInProtocol(val minor: Int)
            """.trimIndent()),
            SourceFile.kotlin("VersionedRpc.kt", """
                package fixture
                import com.virjar.tk.protocol.SinceProtocol
                import com.virjar.tk.protocol.RemovedInProtocol
                import com.virjar.tk.protocol.rpc.RpcService
                import com.virjar.tk.protocol.rpc.RpcMethod

                @RpcService("versioned")
                @SinceProtocol(0)
                interface VersionedRpc {
                    @RpcMethod(1) @SinceProtocol(1) suspend fun future()
                    @RpcMethod(2) @RemovedInProtocol(0) suspend fun expired()
                }
            """.trimIndent()),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "@SinceProtocol minor=1 must be in 0..0")
        assertContains(result.messages, "must be retired at minimum=0")
    }

    @Test
    fun `invalid IDL fails processing with actionable diagnostics`() {
        val result = compile(
            RPC_ANNOTATIONS,
            SourceFile.kotlin(
                "FakeProto.kt",
                """
                package com.virjar.tk.protocol.model

                class LooksLikeProto
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "InvalidRpc.kt",
                """
                package fixture

                import com.virjar.tk.protocol.model.LooksLikeProto
                import com.virjar.tk.protocol.rpc.RpcMethod
                import com.virjar.tk.protocol.rpc.RpcService

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

    @Test
    fun `reserved methodId cannot be reused in the current baseline`() {
        val result = compile(
            RPC_ANNOTATIONS,
            SourceFile.kotlin(
                "ReservedMethodRpc.kt",
                """
                package fixture

                import com.virjar.tk.protocol.rpc.RpcMethod
                import com.virjar.tk.protocol.rpc.RpcReservedMethodIds
                import com.virjar.tk.protocol.rpc.RpcService

                @RpcService("document")
                @RpcReservedMethodIds(9)
                interface ReservedMethodRpc {
                    @RpcMethod(9)
                    suspend fun recreateFolder()

                    @RpcMethod(10)
                    suspend fun createDocument()
                }
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "service [document] methodId=9 已被当前基线保留，不得复用")
    }

    @Test
    fun `duplicate wire routes and generated class names fail before generation`() {
        val result = compile(
            RPC_ANNOTATIONS,
            SourceFile.kotlin(
                "FirstRpc.kt",
                """
                package first
                import com.virjar.tk.protocol.rpc.RpcMethod
                import com.virjar.tk.protocol.rpc.RpcService

                @RpcService("shared-route")
                interface SharedNameRpc {
                    @RpcMethod(1) suspend fun call()
                }
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "SecondRpc.kt",
                """
                package second
                import com.virjar.tk.protocol.rpc.RpcMethod
                import com.virjar.tk.protocol.rpc.RpcService

                @RpcService("shared-route")
                interface OtherNameRpc {
                    @RpcMethod(1) suspend fun call()
                }
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "ThirdRpc.kt",
                """
                package third
                import com.virjar.tk.protocol.rpc.RpcMethod
                import com.virjar.tk.protocol.rpc.RpcService

                @RpcService("another-route")
                interface SharedNameRpc {
                    @RpcMethod(1) suspend fun call()
                }
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "@RpcService name=[shared-route] 重复分配: first.SharedNameRpc, second.OtherNameRpc")
        assertContains(result.messages, "RPC 接口名 [SharedNameRpc] 生成类冲突:")
        assertContains(result.messages, "third.SharedNameRpc")
    }

    private fun compile(vararg sources: SourceFile): JvmCompilationResult =
        KotlinCompilation().apply {
            inheritClassPath = true
            useKsp2()
            this.sources = sources.toList()
            symbolProcessorProviders += RpcProcessorProvider()
            // kctfork 0.12.1 将该属性保留为可空，而当前编译器的 setter 是非空的。
            optIn = emptyList()
            verbose = false
        }.compile()

    private companion object {
        val RPC_ANNOTATIONS = SourceFile.kotlin(
            "RpcAnnotations.kt",
            """
            package com.virjar.tk.protocol.rpc

            @Target(AnnotationTarget.CLASS)
            @Retention(AnnotationRetention.SOURCE)
            annotation class RpcService(val name: String)

            @Target(AnnotationTarget.CLASS)
            @Retention(AnnotationRetention.SOURCE)
            annotation class RpcReservedMethodIds(vararg val ids: Int)

            @Target(AnnotationTarget.FUNCTION)
            @Retention(AnnotationRetention.SOURCE)
            annotation class RpcMethod(val id: Int)
            """.trimIndent(),
        )
    }
}
