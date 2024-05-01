package com.virjar.tk.server.service.base.metric.mql.func;

import com.virjar.tk.server.service.base.metric.EChart4MQL;
import com.virjar.tk.server.service.base.metric.MetricVo;
import com.virjar.tk.server.service.base.metric.mql.Context;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.math.NumberUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@MQLFunction.MQL_FUNC("topN")
public class FuncTopN extends MQLFunction {
    private final String varName;
    private final Integer n;
    private final boolean revers;

    public FuncTopN(List<String> params) {
        super(params);
        if (params.size() < 1) {
            throw new IllegalStateException("must has one param");
        }
        varName = params.get(0);
        if (params.size() >= 2) {
            n = NumberUtils.toInt(params.get(1), 10);
        } else {
            n = 10;
        }

        revers = params.size() >= 3 && BooleanUtils.toBoolean(params.get(2));

    }


    @Override
    public Context.MQLVar call(Context context) {
        Context.MQLVar var = context.getVariables().get(varName);
        if (var == null) {
            throw new IllegalStateException("no var : " + varName);
        }

        Map<String, Double> legendIdValues = Maps.newHashMap();

        var.data.values().forEach(metricVos -> metricVos.forEach(metricVo -> {
            String key = EChart4MQL.legendId(varName, metricVo, true);
            double addValue = legendIdValues.computeIfAbsent(key, (it) -> 0D) + metricVo.getValue();
            legendIdValues.put(key, addValue);
        }));

        Set<String> keepLines = legendIdValues.entrySet().stream()
                .sorted((o1, o2) -> revers ? Double.compare(o1.getValue(), o2.getValue()) : Double.compare(o2.getValue(), o1.getValue()))
                .limit(n).map(Map.Entry::getKey).collect(Collectors.toSet());


        TreeMap<String, List<MetricVo>> newData = new TreeMap<>();

        var.data.forEach((s, metricVos) -> newData.put(s,
                metricVos.stream()
                        .filter(metricVo -> keepLines.contains(EChart4MQL.legendId(varName, metricVo, true)))
                        .collect(Collectors.toList()))
        );
        return Context.MQLVar.newVar(newData);
    }
}
