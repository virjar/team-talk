//package com.virjar.tk.server.system;
//
//import com.virjar.tk.server.service.base.env.Constants;
//import com.virjar.tk.server.utils.ReflectUtil;
//import com.fasterxml.classmate.TypeResolver;
//import com.google.common.base.Optional;
//import jakarta.annotation.Resource;
//import org.apache.commons.lang3.StringUtils;
//import org.springframework.stereotype.Component;
//import springfox.documentation.builders.OperationBuilder;
//import springfox.documentation.builders.ParameterBuilder;
//import springfox.documentation.schema.ModelRef;
//import springfox.documentation.service.Parameter;
//import springfox.documentation.spi.DocumentationType;
//import springfox.documentation.spi.service.OperationBuilderPlugin;
//import springfox.documentation.spi.service.contexts.OperationContext;
//
//import java.util.List;
//
//@Component
//public class ApiTokenSwaggerInjector implements OperationBuilderPlugin {
//
//    @Resource
//    private TypeResolver typeResolver;
//
//    @Override
//    public void apply(OperationContext context) {
//        Optional<LoginRequired> optional = context.findAnnotation(LoginRequired.class);
//        if (!optional.isPresent()) {
//            return;
//        }
//        LoginRequired loginRequired = optional.get();
//
//        OperationBuilder operationBuilder = context.operationBuilder();
//        String newNotes = StringUtils.trimToEmpty(ReflectUtil.getFieldValue(operationBuilder, "notes"));
//
//        if (loginRequired.forAdmin()) {
//            newNotes += " 该接口仅限管理员可以调用";
//        }
//        if (loginRequired.apiToken()) {
//            newNotes += " 该接口支持APIToken调用";
//        }
//        if (StringUtils.isNotBlank(newNotes)) {
//            operationBuilder.notes(newNotes);
//        }
//
//        List<Parameter> parameters = ReflectUtil.getFieldValue(operationBuilder, "parameters");
//        parameters.add(new ParameterBuilder()
//                .name(Constants.userLoginTokenKey)
//                .description("接口Token")
//                .required(true)
//                .allowMultiple(false)
//                .type(typeResolver.resolve(String.class))
//                .modelRef(new ModelRef("string"))
//                .parameterType("query")
//                .build()
//        );
//    }
//
//    @Override
//    public boolean supports(DocumentationType documentationionType) {
//        return true;
//    }
//}
