package com.virjar.tk.server.service.base.metric.mql.compile;

import com.virjar.tk.server.utils.Md5Utils;
import com.virjar.tk.server.service.base.metric.mql.func.FuncFilter;
import com.virjar.tk.server.service.base.metric.mql.Context;
import com.virjar.tk.server.service.base.metric.mql.MQL;
import com.virjar.tk.server.service.base.metric.mql.MetricOperator;
import com.virjar.tk.server.service.base.metric.mql.func.FuncGetVar;
import com.virjar.tk.server.service.base.metric.mql.func.MQLFunction;
import com.google.common.collect.Lists;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

public class MQLCompiler {
    private static final int maxCache = 1024;

    private static final Map<String, MQL> cache = Collections.synchronizedMap(new LinkedHashMap<String, MQL>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, MQL> eldest) {
            return size() > maxCache;
        }
    });

    public static MQL compile(String script) {
        if (StringUtils.isBlank(script)) {
            throw new IllegalStateException("empty script content");
        }
        return cache.computeIfAbsent(Md5Utils.md5Hex(script), s -> doCompile(script));
    }

    @SneakyThrows
    private static MQL doCompile(String script) {
        WordReader tokenReader = new WordReader(
                new LineNumberReader(new StringReader(script)), "mql main script"
        );
        List<MQL.Statement> statements = Lists.newArrayList();
        String firstToken = tokenReader.nextWord();
        if (firstToken == null) {
            throw new BadGrammarException("empty script code");
        }
        while (firstToken != null) {
            if (firstToken.equals(";")) {
                // empty statement
                continue;
            }
            String op = tokenReader.nextWord();
            if (op == null) {
                throw new BadGrammarException("compile failed, bad code at: " + tokenReader.lineLocationDescription());
            }
            if ("(".equals(op)) {
                // this is a None variable declare function call
                // show(successRate);
                tokenReader.pushBack("(");
                tokenReader.pushBack(firstToken);
                Function<Context, Object> function = parseExpression(tokenReader);
                statements.add(new MQL.VoidFunCallStatement(function));
            } else if ("=".equals(op)) {
                // this is a variable declare
                // taskEnd = aggregate(taskEnd,'serverId');
                Function<Context, Object> exp = parseExpression(tokenReader);
                statements.add(new MQL.VarStatement(firstToken, exp));
            } else {
                throw new BadGrammarException("unexpected token: " + firstToken + "\n" + tokenReader.locationDescription());
            }

            firstToken = tokenReader.nextWord();
            if (firstToken == null) {
                break;
            }
            while (firstToken.equals(";")) {
                firstToken = tokenReader.nextWord();
                if (firstToken == null) {
                    break;
                }
            }
        }

        return new MQL(statements);
    }

    private static Function<Context, Object> parseExpression(WordReader tokenReader) throws IOException {
        List<String> tokenStream = Lists.newArrayList();

        Map<String, List<String>> funcDefine = new HashMap<>();
        Map<String, List<String>> filterDefine = new HashMap<>();
        while (true) {
            String token = tokenReader.nextWord();
            if (token == null || token.equals(";")) {
                break;
            }
            if (tokenStream.isEmpty()) {
                // first token
                tokenStream.add(token);
                continue;
            }
            String preToken = tokenStream.get(tokenStream.size() - 1);
            if (token.equals("(")) {
                if (StringUtils.equalsAny(preToken, "+", "-", "*", "/", "(")) {
                    // this is an expression
                    tokenStream.add(token);
                    continue;
                }
                if (MQLFunction.isFunctionNotDefined(preToken)) {
                    throw new BadGrammarException("no function \"" + preToken + "\" defined\n" + tokenReader.locationDescription());
                }
                // this is a function call
                StringBuilder sb = new StringBuilder(preToken);
                sb.append("(");

                List<String> funcBody = Lists.newArrayList();
                funcBody.add(preToken);
                while (true) {
                    String param = tokenReader.nextWord();
                    if (param == null || param.equals(";")) {
                        throw new BadGrammarException("error function call: " + token + ", " + tokenReader.locationDescription());
                    }
                    if (param.equals(")")) {
                        sb.append(param);
                        break;
                    }
                    sb.append(param);
                    if (!",".equals(param)) {
                        funcBody.add(param);
                    }
                }
                String fun = sb.toString();

                tokenStream.set(tokenStream.size() - 1, fun);
                funcDefine.put(fun, funcBody);
            } else if (token.equals("[")) {
                // this is a variable filter
                StringBuilder sb = new StringBuilder(preToken);
                sb.append("[");

                List<String> filterExp = Lists.newArrayList();
                filterExp.add(preToken);

                while (true) {
                    String param = tokenReader.nextWord();
                    if (param == null) {
                        throw new BadGrammarException("error function call: " + token + ", " + tokenReader.locationDescription());
                    }
                    if (param.equals("]")) {
                        sb.append(param);
                        break;
                    }
                    sb.append(param);
                    if (!",".equals(param) && !"=".equals(param)) {
                        filterExp.add(param);
                    }
                }
                String filterExpStr = sb.toString();
                tokenStream.set(tokenStream.size() - 1, filterExpStr);
                filterDefine.put(filterExpStr, filterExp);
            } else {
                if (preToken.equals("-") && (
                        tokenStream.size() == 1 || (StringUtils.equalsAny(tokenStream.get(tokenStream.size() - 2), "+", "-", "*", "/", "("))
                )) {
                    tokenStream.set(tokenStream.size() - 1, "-" + token);
                } else {
                    tokenStream.add(token);
                }
            }
        }

        List<String> reversePolishNotation = Lists.newArrayList();
        Stack<Op> tmpOpStack = new Stack<>();
        tmpOpStack.push(Op.FUCK);

        for (String token : tokenStream) {
            if (token.equals("(")) {
                tmpOpStack.push(Op.FUCK_BRACKETS);
                continue;
            }
            Op op = Op.getBySymbol(token);
            if (op != null) {
                while (true) {
                    Op preOp = tmpOpStack.peek();
                    if (preOp.equals(Op.FUCK_BRACKETS)) {
                        tmpOpStack.push(op);
                        break;
                    }
                    int i = op.priority.compareTo(preOp.priority);
                    if (i <= 0) {
                        tmpOpStack.pop();
                        reversePolishNotation.add(preOp.symbol);
                        continue;
                    }

                    tmpOpStack.push(op);
                    break;
                }
            } else if (token.equals(")")) {
                while (true) {
                    Op pop = tmpOpStack.pop();
                    if (pop.equals(Op.FUCK_BRACKETS)) {
                        break;
                    }
                    reversePolishNotation.add(pop.symbol);
                }
            } else {
                reversePolishNotation.add(token);
            }
        }

        while (!tmpOpStack.peek().equals(Op.FUCK)) {
            reversePolishNotation.add(tmpOpStack.pop().symbol);
        }

        Stack<Function<Context, Object>> computeStack = new Stack<>();
        for (String token : reversePolishNotation) {
            Op op = Op.getBySymbol(token);
            if (op != null) {
                Function<Context, Object> right = computeStack.pop();
                Function<Context, Object> left = computeStack.pop();
                computeStack.push(op.func.apply(left, right));
                continue;
            }

            List<String> funcBody = funcDefine.get(token);
            if (funcBody != null) {
                computeStack.push(MQLFunction.createFunction(funcBody.get(0), funcBody.subList(1, funcBody.size())).asOpNode());
                continue;
            }
            List<String> filterExp = filterDefine.get(token);
            if (filterExp != null) {
                // a [p1=v1,p2=v2]
                computeStack.push(new FuncFilter(filterExp).asOpNode());
                continue;
            }

            FuncGetVar funcGetVar = new FuncGetVar(Lists.newArrayList(token));
            computeStack.push(context -> {
                // call a variable
                Context.MQLVar mqlVar = funcGetVar.call(context);
                if (mqlVar != null) {
                    return mqlVar;
                }
                // a number
                return Double.parseDouble(token);
            });
        }
        return computeStack.pop();
    }


    private enum Op {
        FUCK_BRACKETS("(", 0, null),
        FUCK("#", 0, null),
        ADD("+", 10, MetricOperator::add),
        MINUS("-", 10, MetricOperator::minus),
        MULTIPLE("*", 100, MetricOperator::multiply),
        DIVIDE("/", 100, MetricOperator::divide);


        Op(String symbol, int priority, BiFunction<Function<Context, Object>,
                Function<Context, Object>, MetricOperator> func) {
            this.symbol = symbol;
            this.priority = priority;
            this.func = func;
        }

        public final String symbol;
        public final Integer priority;
        public final BiFunction<Function<Context, Object>, Function<Context, Object>, MetricOperator> func;

        public static Op getBySymbol(String token) {
            for (Op op : values()) {
                if (token.equals(op.symbol)) {
                    return op;
                }
            }
            return null;
        }
    }


    public static class BadGrammarException extends RuntimeException {
        public BadGrammarException(String message) {
            super(message);
        }
    }
}
