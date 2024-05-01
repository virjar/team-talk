package com.virjar.tk.server.service.base.metric.mql.func;

import com.virjar.tk.server.service.base.metric.MetricVo;
import com.virjar.tk.server.service.base.metric.mql.Context;
import com.google.common.collect.Sets;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@MQLFunction.MQL_FUNC("dropLeft")
public class FuncDropLeft extends MQLFunction {
    private final int count;
    private final String var;

    public FuncDropLeft(List<String> params) {
        super(params);
        if (params.isEmpty()) {
            throw new IllegalStateException("shift must has one param");
        }
        var = params.get(0);
        if (params.size() == 1) {
            count = 1;
        } else {
            count = Integer.parseInt(params.get(1));
        }
    }

    private Context.MQLVar requireVar(Context context) {
        Context.MQLVar mqlVar = context.getVariables().get(var);
        if (mqlVar == null) {
            throw new IllegalStateException("no var : " + var);
        }
        return mqlVar.copy();
    }

    @Override
    public Context.MQLVar call(Context context) {
        Context.MQLVar mqlVar = requireVar(context);

        Set<String> allMetricByTagId = mqlVar.getData().values().stream()
                .flatMap((Function<List<MetricVo>, Stream<String>>) metricVos -> metricVos.stream().map(MetricVo::toTagId))
                .collect(Collectors.toSet());

        Set<String> emptyTimeKeys = Sets.newHashSet();

        allMetricByTagId.forEach(metricTagId -> {
            int findCount = 0;
            for (Map.Entry<String, List<MetricVo>> entry : mqlVar.data.entrySet()) {
                List<MetricVo> metricVos = entry.getValue();
                for (int i = 0; i < metricVos.size(); i++) {
                    MetricVo metricVo = metricVos.get(i);
                    if (metricVo.toTagId().equals(metricTagId)) {
                        metricVos.remove(metricVo);
                        findCount++;
                        break;
                    }
                }
                if (metricVos.isEmpty()) {
                    emptyTimeKeys.add(entry.getKey());
                }
                if (findCount >= count) {
                    break;
                }
            }
        });
        emptyTimeKeys.forEach(s -> mqlVar.getData().remove(s));
        return mqlVar;
    }
}
