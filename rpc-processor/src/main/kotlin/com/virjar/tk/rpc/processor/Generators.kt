package com.virjar.tk.rpc.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/** 每个service 生成一个文件：Contract（常量+参数编码） + Stub（派发+解码） + Proxy。 */
class ServiceFileGenerator(private val svc: ServiceModel, private val codeGenerator: CodeGenerator) {

    fun generate() {
        val imports = linkedSetOf(
            "com.virjar.tk.client.RpcInvoker",
            "com.virjar.tk.client.ensureSuccess",
            "com.virjar.tk.protocol.PacketBuffer",
            "com.virjar.tk.protocol.ProtoCodec",
            "com.virjar.tk.rpc.RpcStub",
        )
        svc.methods.forEach { m ->
            m.params.filter { it.isProto }.forEach { imports.add(it.typeName) }
            if (m.ret.isProto) imports.add(m.ret.typeName)
            if (m.ret.isList && m.ret.listArg != null && m.ret.listArg != "kotlin.String") imports.add(m.ret.listArg)
        }
        imports.add("${svc.pkg}.${svc.interfaceName}")

        codeGenerator.createNewFileByPath(Dependencies.ALL_FILES, "rpc/gen/${svc.interfaceName}Grpc", "kt").use { out ->
            out.write(buildString {
                appendLine("// ⚠️ 由 rpc-processor 生成，勿手改。IDL: ${svc.pkg}.${svc.interfaceName}")
                appendLine("@file:Suppress(\"unused\", \"RedundantVisibilityModifier\")")
                appendLine()
                appendLine("package com.virjar.tk.rpc.gen")
                appendLine()
                imports.forEach { appendLine("import $it") }
                appendLine()
                appendLine(contract())
                appendLine()
                appendLine(stub())
                appendLine()
                appendLine(proxy())
            }.toByteArray())
        }
    }

    /** 参数编码语句（write*）——双端唯一事实源（Stub 解码与 Proxy 编码共用此顺序）。 */
    private fun encodeBody(m: MethodModel): String = m.params.joinToString("\n            ") { p ->
        when {
            p.typeName == "kotlin.String" -> "writeString(${p.name})"
            p.typeName == "kotlin.Int" -> "writeVarInt(${p.name})"
            p.typeName == "kotlin.Long" -> "writeVarLong(${p.name})"
            p.typeName == "kotlin.Boolean" -> "writeByte(if (${p.name}) 1 else 0)"
            p.isStringList -> "writeVarInt(${p.name}.size); ${p.name}.forEach { writeString(it) }"
            p.isProto -> "${p.name}.writeTo(this)"
            else -> "// unsupported"
        }
    }

    /** 参数解码语句（val 声明）——与 encodeBody 字段序严格对称。 */
    private fun decodeStmts(m: MethodModel): String = m.params.joinToString("\n                ") { p ->
        when {
            p.typeName == "kotlin.String" -> "val ${p.name} = ${if (p.nullable) "buf.readString()" else "buf.readString()!!"}"
            p.typeName == "kotlin.Int" -> "val ${p.name} = buf.readVarInt()"
            p.typeName == "kotlin.Long" -> "val ${p.name} = buf.readVarLong()"
            p.typeName == "kotlin.Boolean" -> "val ${p.name} = buf.readByte() != 0"
            p.isStringList -> "val n${p.name.replaceFirstChar { it.uppercase() }} = buf.readVarInt(); val ${p.name} = (0 until n${p.name.replaceFirstChar { it.uppercase() }}).map { buf.readString()!! }"
            p.isProto -> "val ${p.name} = ${p.short}.readFrom(buf)"
            else -> "// unsupported"
        }
    }

    private fun signature(m: MethodModel): String {
        val args = m.params.joinToString(", ") { "${it.name}: ${it.short}${if (it.nullable) "?" else ""}" }
        val ret = when {
            m.ret.isUnit -> "Unit"
            m.ret.isList -> "List<${if (m.ret.listArg == "kotlin.String") "String" else m.ret.listArgShort}>"
            else -> m.ret.short
        }
        return "suspend fun ${m.name}($args): $ret"
    }

    private fun retEncodeExpr(m: MethodModel, rv: String): String = when {
        m.ret.isUnit -> "ByteArray(0)"
        m.ret.isList && m.ret.listArg == "kotlin.String" ->
            "ProtoCodec.encodePayload { writeVarInt($rv.size); $rv.forEach { writeString(it) } }"
        m.ret.isList -> "ProtoCodec.encodeList($rv)"
        m.ret.typeName == "kotlin.String" -> "ProtoCodec.encodePayload { writeString($rv) }"
        m.ret.typeName == "kotlin.Int" -> "ProtoCodec.encodePayload { writeVarInt($rv) }"
        m.ret.typeName == "kotlin.Long" -> "ProtoCodec.encodePayload { writeVarLong($rv) }"
        m.ret.typeName == "kotlin.Boolean" -> "ProtoCodec.encodePayload { writeByte(if ($rv) 1 else 0) }"
        else -> "ProtoCodec.encode($rv)"
    }

    private fun retDecodeExpr(m: MethodModel, payloadExpr: String): String {
        val body = when {
            m.ret.isList && m.ret.listArg == "kotlin.String" ->
                "val n = readVarInt(); (0 until n).map { readString()!! }"
            m.ret.isList -> "val n = readVarInt(); (0 until n).map { ${m.ret.listArgShort}.readFrom(this) }"
            m.ret.typeName == "kotlin.String" -> "readString()!!"
            m.ret.typeName == "kotlin.Int" -> "readVarInt()"
            m.ret.typeName == "kotlin.Long" -> "readVarLong()"
            m.ret.typeName == "kotlin.Boolean" -> "readByte() != 0"
            else -> "${m.ret.short}.readFrom(this)"
        }
        return "ProtoCodec.withPayload($payloadExpr) { $body }"
    }

    private fun contract(): String = buildString {
        appendLine("/** ${svc.interfaceName} 契约：serviceId + 方法路由表 + 参数编码（双端唯一事实源）。 */")
        appendLine("object ${svc.contractName} {")
        appendLine("    const val SERVICE = \"${svc.name}\"")
        svc.methods.forEach { appendLine("    const val ${it.constName} = ${it.id}") }
        svc.methods.filter { it.params.isNotEmpty() }.forEach { m ->
            appendLine()
            appendLine("    fun encode${m.name.replaceFirstChar { it.uppercase() }}(${m.params.joinToString(", ") { p -> "${p.name}: ${p.short}${if (p.nullable) "?" else ""}" }}): ByteArray =")
            appendLine("        ProtoCodec.encodePayload {")
            appendLine("            ${encodeBody(m)}")
            appendLine("        }")
        }
        appendLine()
        appendLine("    /** 生成物自检：基本类型参数 encode→逐字段解码 round-trip（ RpcGeneratedChecks 聚合调用）。 */")
        appendLine("    fun verifyRoundTrip() {")
        svc.methods.filter { m -> m.params.isNotEmpty() && m.params.all { !it.isProto && !it.isStringList } }.forEach { m ->
            val samples = m.params.joinToString(", ") { p ->
                when (p.typeName) {
                    "kotlin.String" -> "\"rt-${p.name}\""
                    "kotlin.Int" -> "77"
                    "kotlin.Long" -> "4200L"
                    "kotlin.Boolean" -> "true"
                    else -> "null"
                }
            }
            appendLine("        run {")
            appendLine("            val bytes = encode${m.name.replaceFirstChar { it.uppercase() }}($samples)")
            appendLine("            val buf = PacketBuffer(io.netty.buffer.Unpooled.wrappedBuffer(bytes))")
            appendLine("            ${m.params.joinToString("\n            ") { p ->
                when (p.typeName) {
                    "kotlin.String" -> "val rt${p.name.replaceFirstChar { it.uppercase() }} = buf.readString()!!"
                    "kotlin.Int" -> "val rt${p.name.replaceFirstChar { it.uppercase() }} = buf.readVarInt()"
                    "kotlin.Long" -> "val rt${p.name.replaceFirstChar { it.uppercase() }} = buf.readVarLong()"
                    "kotlin.Boolean" -> "val rt${p.name.replaceFirstChar { it.uppercase() }} = buf.readByte() != 0"
                    else -> ""
                }
            }}")
            val checks = m.params.joinToString(" && ") { p ->
                when (p.typeName) {
                    "kotlin.String" -> "rt${p.name.replaceFirstChar { it.uppercase() }} == \"rt-${p.name}\""
                    "kotlin.Int" -> "rt${p.name.replaceFirstChar { it.uppercase() }} == 77"
                    "kotlin.Long" -> "rt${p.name.replaceFirstChar { it.uppercase() }} == 4200L"
                    "kotlin.Boolean" -> "rt${p.name.replaceFirstChar { it.uppercase() }}"
                    else -> "true"
                }
            }
            appendLine("            check($checks) { \"${svc.name}.${m.name} round-trip failed\" }")
            appendLine("        }")
        }
        appendLine("    }")
        appendLine("}")
    }

    private fun stub(): String = buildString {
        appendLine("/** 服务端 Stub：uid 收敛为成员；dispatch = 解码参数→调用实现→编码返回。 */")
        appendLine("abstract class ${svc.stubName}(uid: String) : RpcStub(uid), ${svc.interfaceName} {")
        appendLine("    override suspend fun dispatch(methodId: Int, payload: ByteArray?): ByteArray {")
        appendLine("        return when (methodId) {")
        svc.methods.forEach { m ->
            appendLine("            ${svc.contractName}.${m.constName} -> {")
            val ret = if (m.ret.isUnit) "" else "val result = "
            if (m.params.isEmpty()) {
                appendLine("                ${ret}${m.name}()")
            } else {
                appendLine("                val buf = PacketBuffer(io.netty.buffer.Unpooled.wrappedBuffer(payload!!))")
                appendLine("                ${decodeStmts(m)}")
                appendLine("                ${ret}${m.name}(${m.params.joinToString(", ") { it.name }})")
            }
            appendLine("                ${if (m.ret.isUnit) "ByteArray(0)" else retEncodeExpr(m, "result")}")
            appendLine("            }")
        }
        appendLine("            else -> throw IllegalArgumentException(\"Unknown method \$methodId for service ${svc.name}\")")
        appendLine("        }")
        appendLine("    }")
        appendLine("}")
    }

    private fun proxy(): String = buildString {
        appendLine("/** 客户端 Proxy：实现 ${svc.interfaceName}，encode→invoke→ensureSuccess→decode。 */")
        appendLine("class ${svc.proxyName}(private val rpc: RpcInvoker) : ${svc.interfaceName} {")
        svc.methods.forEach { m ->
            appendLine("    override ${signature(m)} {")
            val encodeArg = if (m.params.isEmpty()) "null"
                else "${svc.contractName}.encode${m.name.replaceFirstChar { it.uppercase() }}(${m.params.joinToString(", ") { it.name }})"
            appendLine("        val resp = rpc.invoke(${svc.contractName}.SERVICE, ${svc.contractName}.${m.constName}, $encodeArg)")
            appendLine("        resp.ensureSuccess()")
            if (!m.ret.isUnit) {
                appendLine("        return ${retDecodeExpr(m, "resp.payload ?: throw IllegalStateException(\"Empty response payload\")")}")
            }
            appendLine("    }")
            appendLine()
        }
        appendLine("}")
    }
}

/** 汇总注册表 + 生成物自检聚合。 */
class RegistryGenerator(private val services: List<ServiceModel>, private val codeGenerator: CodeGenerator) {
    fun generate() {
        codeGenerator.createNewFileByPath(Dependencies.ALL_FILES, "rpc/gen/RpcServiceRegistry", "kt").use { out ->
            out.write(buildString {
                appendLine("// ⚠️ 由 rpc-processor 生成，勿手改。")
                appendLine("package com.virjar.tk.rpc.gen")
                appendLine()
                appendLine("import com.virjar.tk.client.RpcInvoker")
                appendLine()
                appendLine("/** 全部 RPC service 的注册表（编译期从 IDL 扫描生成）。 */")
                appendLine("object RpcServiceRegistry {")
                appendLine("    /** serviceId → 客户端 Proxy 工厂。 */")
                appendLine("    val proxyFactories: Map<String, (RpcInvoker) -> Any> = mapOf(")
                services.forEach { appendLine("        \"${it.name}\" to { rpc: RpcInvoker -> ${it.proxyName}(rpc) },") }
                appendLine("    )")
                appendLine()
                appendLine("    val serviceNames: List<String> = listOf(${services.joinToString(", ") { "\"${it.name}\"" }})")
                appendLine()
                appendLine("    /** 全部生成物自检（commonTest 的 RpcGeneratedChecksTest 调用）。 */")
                appendLine("    fun verifyAll() {")
                services.forEach { appendLine("        ${it.contractName}.verifyRoundTrip()") }
                appendLine("    }")
                appendLine("}")
            }.toByteArray())
        }
    }
}
