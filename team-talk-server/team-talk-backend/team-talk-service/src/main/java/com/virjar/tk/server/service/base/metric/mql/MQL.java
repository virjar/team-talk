package com.virjar.tk.server.service.base.metric.mql;

import com.virjar.tk.server.service.base.metric.mql.compile.MQLCompiler;
import com.virjar.tk.server.service.base.metric.MetricEnums;
import com.virjar.tk.server.service.base.metric.MetricService;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class MQL {
    private final List<Statement> statements;

    public MQL(List<Statement> statements) {
        this.statements = statements;
    }

    public Map<String, Context.MQLVar> run(MetricEnums.MetricAccuracy metricAccuracy, MetricService metricService) {
        Context context = new Context(metricAccuracy, metricService);
        for (Statement statement : statements) {
            statement.run(context);
        }
        return context.getExportLines();
    }


    public static MQL compile(String mqlCode) {
        return MQLCompiler.compile(mqlCode);
    }

    /**
     * a statement is a simple process pass of mql<br/>
     * <ul>
     *     <li>simple function call</li>
     *     <li>define var by response of function call</li>
     *     <li>binary operator of number 、MQLVar、function call result</li>
     *     <li>combine of binary operator </li>
     * </ul>
     */
    public interface Statement {
        void run(Context context);
    }

    /**
     * declare a mql variable and setup with an expression calculate result
     */
    public static class VarStatement implements Statement {
        private final String var;
        private final Function<Context, ?> expression;

        public VarStatement(String var, Function<Context, ?> expression) {
            this.var = var;
            this.expression = expression;
        }

        @Override
        public void run(Context context) {
            Object mqlVar = expression.apply(context);
            if (mqlVar == null) {
                return;
            }
            if (!(mqlVar instanceof Context.MQLVar)) {
                throw new IllegalStateException("the exp mast be a MQLVar");
            }
            context.getVariables().put(var, (Context.MQLVar) mqlVar);
        }
    }

    /**
     * just call a function, but no result setup to variable,
     * like "show(successRate)"
     */
    public static class VoidFunCallStatement implements Statement {

        private final Function<Context, Object> mqlFunction;

        public VoidFunCallStatement(Function<Context, Object> mqlFunction) {
            this.mqlFunction = mqlFunction;
        }

        @Override
        public void run(Context context) {
            mqlFunction.apply(context);
        }
    }
}
