package com.virjar.tk.server.sys.service.metric.mql.func;

import com.virjar.tk.server.sys.service.metric.mql.Context;
import com.google.common.collect.Maps;

import java.util.List;

@MQLFunction.MQL_FUNC("show")
public class FuncShow extends MQLFunction {
    public FuncShow(List<String> params) {
        super(params);
    }


    @Override
    public Context.MQLVar call(Context context) {
        for (String var : params) {
            Context.MQLVar line = context.getVariables().get(var);
            if (line == null) {
                throw new IllegalStateException("no var: " + var);
            }
            context.getExportLines().put(var, line);
        }
        return Context.MQLVar.newVar(Maps.newTreeMap());
    }
}
