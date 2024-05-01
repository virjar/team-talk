package com.virjar.tk.server.service.base.metric.mql.func;

import com.virjar.tk.server.service.base.metric.mql.compile.MQLCompiler;
import com.virjar.tk.server.service.base.metric.mql.Context;
import com.google.common.collect.Maps;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public abstract class MQLFunction {

    protected List<String> params;

    public MQLFunction(List<String> params) {
        this.params = params;
    }


    /**
     * call this function, the result is a new Context.MQLVar node
     */
    public abstract Context.MQLVar call(Context context);

    /**
     * any mql function can be a supplier of MetricOperator param,
     * and then the result of function call can be add、minus、multiple、divide
     */
    public Function<Context, Object> asOpNode() {
        return this::call;
    }


    public static Map<String, Constructor<? extends MQLFunction>> functionRegistry = Maps.newHashMap();

    static {
        registryFunc(FuncAggregate.class);
        registryFunc(FuncFilter.class);
        registryFunc(FuncMetric.class);
        registryFunc(FuncShow.class);
        registryFunc(FuncGetVar.class);
        registryFunc(FuncShift.class);
        registryFunc(FuncDropLeft.class);
        registryFunc(FuncTopN.class);
    }

    @SneakyThrows
    private static void registryFunc(Class<? extends MQLFunction> clazz) {
        MQL_FUNC mqlFunc = clazz.getAnnotation(MQL_FUNC.class);
        if (mqlFunc == null) {
            throw new IllegalStateException("error function registry for class: " + clazz);
        }
        String funcName = mqlFunc.value();
        if (StringUtils.isBlank(funcName)) {
            throw new RuntimeException("empty funcName");
        }
        if (functionRegistry.containsKey(funcName)) {
            throw new IllegalStateException("duplicate registry for function: " + funcName);
        }
        Constructor<? extends MQLFunction> constructor = clazz.getConstructor(List.class);
        functionRegistry.put(mqlFunc.value(), constructor);
    }

    @SneakyThrows
    public static MQLFunction createFunction(String name, List<String> params) {
        Constructor<? extends MQLFunction> constructor = functionRegistry.get(name);
        if (constructor == null) {
            throw new MQLCompiler.BadGrammarException("no function: " + name + " defined");
        }
        return constructor.newInstance(params);
    }

    public static boolean isFunctionNotDefined(String name) {
        return !functionRegistry.containsKey(name);
    }

    @Target({ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface MQL_FUNC {
        /**
         * @return the name of this function
         */
        String value();
    }
}
