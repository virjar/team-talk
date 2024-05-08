package com.virjar.tk.server.sys.service.metric.mql.func;

import com.virjar.tk.server.sys.service.metric.mql.compile.MQLCompiler;
import com.virjar.tk.server.sys.service.metric.MetricVo;
import com.virjar.tk.server.sys.service.metric.mql.Context;
import com.google.common.collect.Maps;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@MQLFunction.MQL_FUNC("metric")
public class FuncMetric extends MQLFunction {

    private final String metricName;
    private final Map<String, String> filters = Maps.newHashMap();


    public FuncMetric(List<String> params) {
        super(params);
        if (params.isEmpty()) {
            throw new IllegalStateException("metric function need param");
        }
        metricName = params.get(0);
        String key = null;
        for (int i = 1; i < params.size(); i++) {
            String token = params.get(i);
            if (token.equals("[") || token.equals("]") || token.equals("=")) {
                continue;
            }
            if (key == null) {
                key = token;
                continue;
            }
            filters.put(key, token);
            key = null;
        }

        if (key != null) {
            throw new MQLCompiler.BadGrammarException("no filter value for key:" + key);
        }

    }


    @Override
    public Context.MQLVar call(Context context) {
        List<MetricVo> metrics = context.getMetricService().queryMetric(metricName, filters, context.getMetricAccuracy());
        Map<String, List<MetricVo>> metricWithTime = metrics.stream().collect(Collectors.groupingBy(MetricVo::getTimeKey));
        return Context.MQLVar.newVar(new TreeMap<>(metricWithTime));
    }
}
