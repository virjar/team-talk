package com.virjar.tk.protocol.rpc.processor

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
        RpcProcessor(environment.codeGenerator, environment.logger, environment.options)
}

/** 判断类型（含继承链）是否实现 com.virjar.tk.protocol.IProto。 */
internal fun com.google.devtools.ksp.symbol.KSType.isIProto(): Boolean {
    val decl = declaration as? com.google.devtools.ksp.symbol.KSClassDeclaration ?: return false
    if (decl.qualifiedName?.asString() == "com.virjar.tk.protocol.IProto") return true
    return decl.superTypes.any { it.resolve().isIProto() }
}

private const val RPC_SERVICE_ANNOTATION = "com.virjar.tk.protocol.rpc.RpcService"
private const val RPC_METHOD_ANNOTATION = "com.virjar.tk.protocol.rpc.RpcMethod"
private const val RPC_RESERVED_METHOD_IDS_ANNOTATION = "com.virjar.tk.protocol.rpc.RpcReservedMethodIds"

/** 注解按完整限定名识别，避免业务源码声明同名注解后被误当成 RPC IDL。 */
internal fun KSAnnotated.findAnnotation(qualifiedName: String): KSAnnotation? = annotations.firstOrNull {
    it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
}

class RpcProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    options: Map<String, String> = emptyMap(),
) : SymbolProcessor {
    private val protocolPolicy = ProtocolBuildPolicy(options)

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
        if (!validateServiceIdentities(services)) return emptyList()

        WireSchemaGenerator(protocolPolicy, logger, codeGenerator).generate(resolver, services)
        if (services.isEmpty()) return emptyList()

        services.forEach { ServiceFileGenerator(it, codeGenerator).generate() }
        RegistryGenerator(services, codeGenerator).generate()
        return emptyList()
    }

    // ── 扫描与校验 ──

    /** wire 路由名与生成类名分别唯一；在写文件前拒绝冲突，避免注册表静默覆盖服务。 */
    private fun validateServiceIdentities(services: List<ServiceModel>): Boolean {
        var valid = true
        services.groupBy { it.name }.filterValues { it.size > 1 }.forEach { (name, duplicates) ->
            logger.error(
                "@RpcService name=[$name] 重复分配: " +
                    duplicates.joinToString { "${it.pkg}.${it.interfaceName}" } +
                    "（service name 是 wire 路由身份，必须全局唯一）",
            )
            valid = false
        }
        services.groupBy { it.interfaceName }.filterValues { it.size > 1 }.forEach { (name, duplicates) ->
            logger.error(
                "RPC 接口名 [$name] 生成类冲突: " +
                    duplicates.joinToString { "${it.pkg}.${it.interfaceName}" } +
                    "（Contract/Stub/Proxy 位于同一生成包，接口简单名必须唯一）",
            )
            valid = false
        }
        return valid
    }

    private fun KSClassDeclaration.toServiceModel(): ServiceModel? {
        val ann = findAnnotation(RPC_SERVICE_ANNOTATION) ?: return null
        val serviceName = ann.arguments.firstOrNull { it.name?.asString() == "name" }?.value as? String
        if (serviceName.isNullOrBlank()) {
            logger.error("@RpcService(name) 必填", this); return null
        }
        val reservedMethodIds = reservedMethodIds()
        val methods = declarations.filterIsInstance<KSFunctionDeclaration>()
            .map { fn -> fn.toMethodModel(this) }
            .toList()
        if (methods.isEmpty() && reservedMethodIds.isEmpty()) {
            logger.error("@RpcService $serviceName 无方法；退役服务必须保留 @RpcReservedMethodIds", this)
        }
        // methodId 完整性：重复/非法 id 编译期报错（否则生成 when 静默错乱 wire）
        methods.filter { it.id > 0 }.groupBy { it.id }.filter { it.value.size > 1 }.forEach { (id, dup) ->
            logger.error(
                "service [$serviceName] methodId=$id 重复分配: ${dup.joinToString { it.name }}" +
                    "（每个方法必须用 @RpcMethod 显式分配唯一 id）", this)
        }
        methods.filter { it.id <= 0 }.forEach {
            logger.error("service [$serviceName] 方法 ${it.name} 的 @RpcMethod id 必须 > 0", this)
        }
        methods.filter { it.id in reservedMethodIds }.forEach {
            logger.error(
                "service [$serviceName] methodId=${it.id} 已被当前基线保留，不得复用: ${it.name}",
                this,
            )
        }
        return ServiceModel(
            serviceName,
            packageName.asString(),
            simpleName.asString(),
            methods,
            reservedMethodIds.toSet(),
        )
    }

    private fun KSClassDeclaration.reservedMethodIds(): List<Int> {
        val annotation = findAnnotation(RPC_RESERVED_METHOD_IDS_ANNOTATION) ?: return emptyList()
        val rawIds = annotation.arguments
            .firstOrNull { it.name?.asString() == "ids" }
            ?.value
        val ids = when (rawIds) {
            is List<*> -> rawIds.filterIsInstance<Int>()
            is Int -> listOf(rawIds)
            else -> emptyList()
        }
        ids.filter { it <= 0 }.forEach { id ->
            logger.error("@RpcReservedMethodIds 仅允许正数，实际 methodId=$id", this)
        }
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.forEach { (id, _) ->
            logger.error("@RpcReservedMethodIds methodId=$id 重复声明", this)
        }
        return ids.filter { it > 0 }
    }

    private fun KSFunctionDeclaration.toMethodModel(service: KSClassDeclaration): MethodModel {
        val methodAnnotation = findAnnotation(RPC_METHOD_ANNOTATION)
        val annId = methodAnnotation?.arguments
            ?.firstOrNull { it.name?.asString() == "id" }
            ?.value as? Int
        if (annId == null) {
            logger.error(
                "@RpcService 方法必须显式声明 @RpcMethod(id): ${simpleName.asString()}",
                this,
            )
        }

        if (!modifiers.contains(Modifier.SUSPEND)) {
            logger.error("@RpcService 方法必须是 suspend: $simpleName", this)
        }

        val params = parameters.mapIndexed { i, p ->
            val resolved = p.type.resolve()
            val typeName = resolved.declaration.qualifiedName?.asString()
            if (typeName == null) {
                logger.error("参数类型无法解析", p)
            }
            if (p.hasDefault) {
                logger.error("IDL 方法参数禁止默认值（保持 wire 明确性）: $simpleName.${p.name?.asString()}", p)
            }
            if (typeName == "kotlin.collections.List") {
                val elementType = resolved.arguments.firstOrNull()?.type?.resolve()
                val elem = elementType?.declaration?.qualifiedName?.asString()
                if (elem != "kotlin.String") {
                    logger.error("List 参数仅支持 List<String>，实际 List<$elem>", p)
                }
                if (elementType?.isMarkedNullable == true) {
                    logger.error("List 参数元素禁止 nullable: $simpleName.${p.name?.asString()}", p)
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
        if (retResolved?.isMarkedNullable == true) {
            logger.error("RPC 返回类型禁止 nullable: ${simpleName.asString()}(): $retQn?", this)
        }
        if (retIsList) {
            val elementType = retResolved?.arguments?.firstOrNull()?.type?.resolve()
            if (elementType?.isMarkedNullable == true) {
                logger.error("List 返回元素禁止 nullable: ${simpleName.asString()}(): List<$retListArg?>", this)
            }
            if (retListArg != "kotlin.String" && elementType?.isIProto() != true) {
                logger.error("List 返回的元素类型仅支持 String/IProto 实现，实际 $retListArg", this)
            }
        } else if (retQn !in TypeCodec.PRIMITIVES && retQn != "kotlin.Unit") {
            if (retResolved?.isIProto() != true) {
                logger.error("返回类型仅支持基础类型/IProto 实现，实际 $retQn", this)
            }
        }
        return MethodModel(
            id = annId ?: 0,
            name = simpleName.asString(),
            params = params,
            ret = ReturnModel(retQn, retIsList, retListArg, retResolved?.isMarkedNullable ?: false),
            availability = protocolPolicy.availability(this, service, logger),
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
data class MethodModel(
    val id: Int,
    val name: String,
    val params: List<ParamModel>,
    val ret: ReturnModel,
    val availability: AvailabilityModel = AvailabilityModel(0, null),
) {
    val constName get() = "M_" + name.replace(Regex("([a-z])([A-Z])")) { m -> "${m.groupValues[1]}_${m.groupValues[2]}" }.uppercase()
}
data class ServiceModel(
    val name: String,
    val pkg: String,
    val interfaceName: String,
    val methods: List<MethodModel>,
    val reservedMethodIds: Set<Int>,
) {
    val contractName get() = "${interfaceName}Contract"
    val stubName get() = "${interfaceName}Stub"
    val proxyName get() = "${interfaceName}Proxy"
}

object TypeCodec {
    val PRIMITIVES = setOf("kotlin.String", "kotlin.Int", "kotlin.Long", "kotlin.Boolean")
}
