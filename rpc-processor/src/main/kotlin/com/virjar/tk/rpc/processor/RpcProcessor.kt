package com.virjar.tk.rpc.processor

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*

/**
 * RPC IDL 代码生成器（KSP2）。
 *
 * 扫描 @RpcService interface，为每个 service 生成一个文件：
 * - `XxxRpcContract`：SERVICE/M_* 常量 + 每方法参数编码（唯一事实源，双端共用）
 * - `XxxRpcStub`：服务端 abstract class（uid 成员，dispatch 解码→调用→编码）
 * - `XxxRpcProxy`：客户端实现（encode→invoke→ensureSuccess→decode）
 * 另生成：`RpcServiceRegistry`（全部 proxy 工厂）+ 每方法基本类型参数 round-trip 测试。
 */
class RpcProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        RpcProcessor(environment.codeGenerator, environment.logger)
}

/** 判断类型（含继承链）是否实现 com.virjar.tk.protocol.IProto。 */
private fun com.google.devtools.ksp.symbol.KSType.isIProto(): Boolean {
    val decl = declaration as? com.google.devtools.ksp.symbol.KSClassDeclaration ?: return false
    if (decl.qualifiedName?.asString() == "com.virjar.tk.protocol.IProto") return true
    return decl.superTypes.any { it.resolve().isIProto() }
}

class RpcProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    /** KSP 多轮处理防护：只生成一轮。 */
    private var generated = false

    override fun process(resolver: Resolver): List<KSFile> {
        if (generated) return emptyList()
        generated = true
        val services = resolver.getAllFiles()
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.INTERFACE }
            .mapNotNull { it.toServiceModel() }
            .sortedBy { it.name }
            .toList()
        if (services.isEmpty()) return emptyList()

        services.forEach { ServiceFileGenerator(it, codeGenerator).generate() }
        RegistryGenerator(services, codeGenerator).generate()
        return emptyList()
    }

    // ── 扫描与校验 ──

    private fun KSClassDeclaration.toServiceModel(): ServiceModel? {
        val ann = annotations.firstOrNull {
            it.annotationType.resolve().declaration.simpleName.asString() == "RpcService"
        } ?: return null
        val serviceName = ann.arguments.firstOrNull { it.name?.asString() == "name" }?.value as? String
        if (serviceName.isNullOrBlank()) {
            logger.error("@RpcService(name) 必填", this); return null
        }
        val methods = declarations.filterIsInstance<KSFunctionDeclaration>()
            .mapIndexed { idx, fn -> fn.toMethodModel(idx + 1) }
            .toList()
        if (methods.isEmpty()) logger.error("@RpcService $serviceName 无方法", this)
        // methodId 完整性：重复/非法 id 编译期报错（否则生成 when 静默错乱 wire）
        methods.groupBy { it.id }.filter { it.value.size > 1 }.forEach { (id, dup) ->
            logger.error(
                "service [$serviceName] methodId=$id 重复分配: ${dup.joinToString { it.name }}" +
                    "（中间插入新方法必须 @RpcMethod 显式锁定 id）", this)
        }
        methods.filter { it.id <= 0 }.forEach {
            logger.error("service [$serviceName] 方法 ${it.name} 的 @RpcMethod id 必须 > 0", this)
        }
        return ServiceModel(serviceName, packageName.asString(), simpleName.asString(), methods)
    }

    private fun KSFunctionDeclaration.toMethodModel(defaultId: Int): MethodModel {
        val annId = annotations.firstOrNull {
            it.annotationType.resolve().declaration.simpleName.asString() == "RpcMethod"
        }?.arguments?.firstOrNull()?.value as? Int

        if (!modifiers.contains(Modifier.SUSPEND)) {
            logger.error("@RpcService 方法必须是 suspend: $simpleName", this)
        }

        val params = parameters.mapIndexed { i, p ->
            val resolved = p.type.resolve()
            val typeName = resolved.declaration.qualifiedName?.asString()
            if (typeName == null) {
                logger.error("参数类型无法解析", p); "kotlin.Nothing"
            } else typeName
            if (p.hasDefault) {
                logger.error("IDL 方法参数禁止默认值（保持 wire 明确性）: $simpleName.${p.name?.asString()}", p)
            }
            if (typeName == "kotlin.collections.List") {
                val elem = resolved.arguments.firstOrNull()?.type?.resolve()?.declaration?.qualifiedName?.asString()
                if (elem != "kotlin.String") {
                    logger.error("List 参数仅支持 List<String>，实际 List<$elem>", p)
                }
                if (resolved.isMarkedNullable) {
                    logger.error("List 参数禁止 nullable", p)
                }
            } else if (typeName?.startsWith("com.virjar.tk.") == true) {
                if (!resolved.isIProto()) {
                    logger.error("参数 ${p.name?.asString()}: $typeName 不是 IProto 实现", p)
                }
                if (resolved.isMarkedNullable) {
                    logger.error("IProto 参数禁止 nullable（wire 布局无 present 位）", p)
                }
            } else if (typeName in TypeCodec.PRIMITIVES && resolved.isMarkedNullable && typeName != "kotlin.String") {
                logger.error("参数 ${p.name?.asString()}: $typeName? 禁止（仅 String 允许 nullable）", p)
            }
            if (typeName != null && typeName !in TypeCodec.PRIMITIVES && typeName != "kotlin.collections.List" && !typeName.startsWith("com.virjar.tk.")) {
                logger.error("参数 ${p.name?.asString()}: $typeName 不在白名单（String/Int/Long/Boolean/List<String>/IProto 子类）", p)
            }
            ParamModel(p.name?.asString() ?: "arg$i", typeName ?: "kotlin.Nothing", resolved.isMarkedNullable)
        }

        val retResolved = returnType?.resolve()
        val retQn = retResolved?.declaration?.qualifiedName?.asString() ?: "kotlin.Unit"
        val retIsList = retQn == "kotlin.collections.List"
        val retListArg = if (retIsList) {
            retResolved?.arguments?.firstOrNull()?.type?.resolve()?.declaration?.qualifiedName?.asString()
        } else null
        if (retIsList) {
            if (retListArg != "kotlin.String" && !(retListArg?.startsWith("com.virjar.tk.") ?: false)) {
                logger.error("List 返回的元素类型仅支持 String/IProto 子类，实际 $retListArg", this)
            }
        } else if (retQn !in TypeCodec.PRIMITIVES && retQn != "kotlin.Unit" && !retQn.startsWith("com.virjar.tk.")) {
            logger.error("返回类型 $retQn 不在白名单", this)
        }
        return MethodModel(
            id = annId ?: defaultId,
            name = simpleName.asString(),
            params = params,
            ret = ReturnModel(retQn, retIsList, retListArg, retResolved?.isMarkedNullable ?: false),
        )
    }
}

// ── 模型 ──

data class ParamModel(val name: String, val typeName: String, val nullable: Boolean) {
    val isStringList get() = typeName == "kotlin.collections.List"
    val isProto get() = typeName !in TypeCodec.PRIMITIVES && typeName != "kotlin.Nothing" && !isStringList
    val short get() = if (isStringList) "List<String>" else typeName.substringAfterLast('.')
}
data class ReturnModel(val typeName: String, val isList: Boolean, val listArg: String?, val nullable: Boolean) {
    val isUnit get() = typeName == "kotlin.Unit"
    val isProto get() = typeName.startsWith("com.virjar.tk.")
    val listArgShort get() = listArg?.substringAfterLast('.')
    val short get() = typeName.substringAfterLast('.')
}
data class MethodModel(val id: Int, val name: String, val params: List<ParamModel>, val ret: ReturnModel) {
    val constName get() = "M_" + name.replace(Regex("([a-z])([A-Z])")) { m -> "${m.groupValues[1]}_${m.groupValues[2]}" }.uppercase()
}
data class ServiceModel(val name: String, val pkg: String, val interfaceName: String, val methods: List<MethodModel>) {
    val contractName get() = "${interfaceName}Contract"
    val stubName get() = "${interfaceName}Stub"
    val proxyName get() = "${interfaceName}Proxy"
}

object TypeCodec {
    val PRIMITIVES = setOf("kotlin.String", "kotlin.Int", "kotlin.Long", "kotlin.Boolean")

    fun writeExpr(p: ParamModel): String = when (p.typeName) {
        "kotlin.String" -> "writeString(${p.name})"
        "kotlin.Int" -> "writeVarInt(${p.name})"
        "kotlin.Long" -> "writeVarLong(${p.name})"
        "kotlin.Boolean" -> "writeByte(if (${p.name}) 1 else 0)"
        else -> null.also { } // IProto 嵌套直写，调用方处理
    } ?: "__proto__"

    fun readExpr(typeName: String, nullable: Boolean, reader: String): String = when (typeName) {
        "kotlin.String" -> if (nullable) "buf.readString()" else "buf.readString()!!"
        "kotlin.Int" -> "buf.readVarInt()"
        "kotlin.Long" -> "buf.readVarLong()"
        "kotlin.Boolean" -> "buf.readByte() != 0"
        else -> reader  // IProto companion readFrom
    }
}
