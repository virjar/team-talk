package com.virjar.tk.server.sys.service.metric.mql.func;

import com.virjar.tk.server.sys.service.metric.MetricEnums;
import com.virjar.tk.server.sys.service.metric.MetricVo;
import com.virjar.tk.server.sys.service.metric.mql.Context;
import com.google.common.collect.Maps;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@MQLFunction.MQL_FUNC("shift")
public class FuncShift extends MQLFunction {
    private final String shiftVal;
    private final Map<MetricEnums.MetricAccuracy, Function<LocalDateTime, LocalDateTime>> shiftFunc = Maps.newHashMap();

    public FuncShift(List<String> params) {
        super(params);
        if (params.isEmpty()) {
            throw new IllegalStateException("shift must has one param");
        }
        shiftVal = params.get(0);
        parseParam(params);
    }


    private void parseParam(List<String> params) {
        int count = 1;
        if (params.size() > 1) {
            count = Integer.parseInt(params.get(1));
        }
        if (params.size() > 2) {
            Function<LocalDateTime, LocalDateTime> func = definitionFun(count, params.get(2));
            shiftFunc.put(MetricEnums.MetricAccuracy.minutes, func);
            shiftFunc.put(MetricEnums.MetricAccuracy.hours, func);
            shiftFunc.put(MetricEnums.MetricAccuracy.days, func);
        } else {
            int finalCount = count;
            shiftFunc.put(MetricEnums.MetricAccuracy.minutes, t -> t.minusMinutes(finalCount));
            shiftFunc.put(MetricEnums.MetricAccuracy.hours, t -> t.minusHours(finalCount));
            shiftFunc.put(MetricEnums.MetricAccuracy.days, t -> t.minusDays(finalCount));
        }
    }

    private Function<LocalDateTime, LocalDateTime> definitionFun(Integer count, String unit) {
        switch (unit.toLowerCase()) {
            case "minute":
                return t -> t.minusMinutes(count);
            case "hour":
                return t -> t.minusHours(count);
            case "day":
                return t -> t.minusDays(count);
            case "month":
                return t -> t.minusMonths(count);
            default:
                throw new IllegalStateException("unknown shift unit");
        }
    }

    @Override
    public Context.MQLVar call(Context context) {
        Context.MQLVar mqlVar = context.getVariables().get(shiftVal);
        if (mqlVar == null) {
            throw new IllegalStateException("no var : " + shiftVal);
        }
        TreeMap<String, List<MetricVo>> ret = Maps.newTreeMap();
        mqlVar.data.forEach((timeKey, metricVos) -> {
            if (metricVos.isEmpty()) {
                return;
            }
            MetricVo nowNode = metricVos.iterator().next();
            LocalDateTime shiftTime = shiftFunc.get(context.getMetricAccuracy()).apply(nowNode.getCreateTime());
            String shiftTimeStr = context.getMetricAccuracy().timePattern.format(shiftTime);
            List<MetricVo> shiftMetrics = mqlVar.getData().get(shiftTimeStr);
            if (shiftMetrics == null) {
                return;
            }

            ret.put(timeKey, shiftMetrics.stream().map(metricVo -> {
                MetricVo ret1 = MetricVo.cloneMetricVo(metricVo);
                ret1.setCreateTime(nowNode.getCreateTime());
                ret1.setTimeKey(timeKey);
                return ret1;
            }).collect(Collectors.toList()));
        });

        return Context.MQLVar.newVar(ret);
    }


}
