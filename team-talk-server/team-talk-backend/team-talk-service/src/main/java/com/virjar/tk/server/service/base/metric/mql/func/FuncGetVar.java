package com.virjar.tk.server.service.base.metric.mql.func;

import com.virjar.tk.server.service.base.metric.mql.Context;

import java.util.List;

@MQLFunction.MQL_FUNC("getVar")
public class FuncGetVar extends MQLFunction {
    private final String varName;

    public FuncGetVar(List<String> params) {
        super(params);
        varName = params.get(0);
    }

    @Override
    public Context.MQLVar call(Context context) {
        Context.MQLVar mqlVar = context.getVariables().get(varName);
        if (mqlVar == null) {
            return null;
        }
        return mqlVar.copy();
    }
}
